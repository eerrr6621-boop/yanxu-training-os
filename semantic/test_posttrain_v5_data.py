"""Pure facts/lineage/export-contract checks. Never reads evaluation questions."""
import copy
import os
from pathlib import Path
import unittest
from posttrain_v5_data import NEW,build_dataset,validate,content_hash
from posttrain_v5_artifacts import CONFIG,PARENT_FILES,MODEL,EXPERIMENT,read,require,sha,verify_parent
from export_posttrain_v5 import build_manifest

class V5DataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.parent=Path(os.environ["V5_PARENT_TRAINING"])
        cls.base=Path(os.environ["V5_BASE_MODEL"])
        cls.snapshot=read(cls.parent/"data-snapshot.json")
        cls.data=build_dataset(cls.snapshot)

    def test_fixed_parent_bytes(self):
        verify_parent(self.parent,self.base)

    def test_exact_whole_row_retention(self):
        self.assertEqual(self.snapshot["train"],self.data["train"][:144])
        self.assertEqual(content_hash(self.snapshot["train"]),content_hash(self.data["train"][:144]))

    def test_counts_and_disjoint_families(self):
        v=validate(self.snapshot)
        self.assertEqual((v["training_phrasings"],v["independent_families"],v["new_families"]),(192,48,12))

    def test_no_validation_or_real_rows(self):
        self.assertEqual(self.data["dev"],[])
        self.assertEqual({r["origin"] for r in self.data["train"][144:]},{"v5_new_synthetic_family"})

    def test_no_inplace_parent_mutation(self):
        actual=build_dataset(self.snapshot);actual["train"][0]["pos"][0]="mutated"
        self.assertNotEqual(actual["train"][0]["pos"],self.snapshot["train"][0]["pos"])

    def test_family_variants_keep_exact_facts(self):
        for family,topic,audience,queries,pos,neg,rationale in NEW:
            rows=[r for r in self.data["train"] if r["family"]=="v5_"+family]
            self.assertEqual(len(rows),4)
            self.assertEqual([r["query"] for r in rows],queries)
            for row in rows:
                self.assertEqual(row["pos"],[pos]);self.assertEqual(row["neg"],neg)
                self.assertEqual(row["factual_passages"],[pos]+neg)
                self.assertIn(topic,pos);self.assertIn(audience,pos)

    def test_all_paraphrases_request_same_event(self):
        for _,_,audience,queries,_,_,_ in NEW:
            for query in queries:
                self.assertIn(audience,query);self.assertIn("独立",query)

    def test_no_lexical_padding_and_no_duplicates(self):
        for row in self.data["train"][144:]:
            self.assertEqual(row["factual_passages"],row["pos"]+row["neg"])
            self.assertEqual(len(set(row["pos"]+row["neg"])),4)

    def test_invalid_parent_snapshot_fails(self):
        for data in ({"train":[],"dev":[]},{"train":self.snapshot["train"],"dev":[{}]}):
            with self.assertRaises(ValueError):build_dataset(data)

    def test_token_length_balance_without_truncation(self):
        from transformers import AutoTokenizer
        from train_posttrain_v5 import length_inventory
        tokenizer=AutoTokenizer.from_pretrained(self.base/"original",local_files_only=True,trust_remote_code=False)
        stats=length_inventory(self.data["train"],tokenizer)
        self.assertEqual(stats["positive_token_length_positions"]["new"],dict(shortest=16,middle=16,longest=16,tied=0))
        self.assertLessEqual(stats["maximum_tokens"],256)

    def test_fixed_config_and_updates(self):
        self.assertEqual(CONFIG["epochs"]*len(self.data["train"])//CONFIG["accumulation"],96)
        self.assertEqual((CONFIG["threads"],CONFIG["learning_rate"],CONFIG["retention_weight"],CONFIG["trainable_prefix"]),(1,3e-6,10.0,"encoder.layer.3."))

    def test_export_identity_not_BAAI(self):
        training=dict(experiment=EXPERIMENT,parent_checkpoint_sha256=PARENT_FILES["checkpoint/model.safetensors"],checkpoint_sha256="a"*64,
            parameter_count=23953920,config=CONFIG,checkpoint_config_sha256="b"*64,checkpoint_tokenizer_sha256={},parent_checkpoint_config_sha256="c"*64)
        frozen=dict(base_manifest={"sha256":{"tokenizer.json":"d"*64}},training_data_sha256="e"*64,parent_model="yanxu/bge-small-zh-anchored-v4",parent_revision="v4-test",base_model_card_sha256="f"*64)
        hashes={"tokenizer.json":"d"*64,"model-fp32.onnx":"1"*64,"model-int8.onnx":"2"*64}
        manifest=build_manifest(training,frozen,hashes,{n:1 for n in hashes})
        self.assertEqual(manifest["model"],MODEL);self.assertFalse(manifest["production_approved"])
        self.assertEqual(manifest["revision"],"v5-"+"a"*16)
        with self.assertRaises(ValueError):build_manifest(training,frozen,{**hashes,"tokenizer.json":"0"*64},{n:1 for n in hashes})
        with self.assertRaises(ValueError):build_manifest({**training,"experiment":"anchored-top-layer-v4"},frozen,hashes,{n:1 for n in hashes})

if __name__=="__main__":unittest.main()
