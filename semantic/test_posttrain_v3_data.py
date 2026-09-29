"""Pure training-data hygiene checks; never imports unseen holdout."""
import unittest
from collections import Counter,defaultdict
from posttrain_v3_data import build_dataset,validate


class DataTests(unittest.TestCase):
    def setUp(self):self.data=build_dataset()
    def test_counts(self):self.assertEqual(validate(),dict(train=96,dev=16,train_families=24,dev_families=8))
    def test_deterministic(self):self.assertEqual(self.data,build_dataset())
    def test_facts_not_changed(self):
        for r in self.data["train"]:
            for actual,fact in zip(r["pos"]+r["neg"],r["factual_passages"]):
                self.assertTrue(actual.startswith(fact+"\n文档编排附录\n"))
    def test_length_labels_balanced_within_each_family(self):
        families=defaultdict(list)
        for r in self.data["train"]:families[r["family"]].append(r)
        for rows in families.values():
            ranks=Counter()
            for r in rows:
                docs=r["pos"]+r["neg"]
                ranks[sorted(range(4),key=lambda i:len(docs[i])).index(0)]+=1
            self.assertEqual(ranks,Counter({0:1,1:1,2:1,3:1}))
    def test_dev_unpadded(self):
        for r in self.data["dev"]:self.assertEqual(r["pos"]+r["neg"],r["factual_passages"])
    def test_origins_disjoint(self):
        facts=lambda split:{t for r in self.data[split] for t in r["factual_passages"]}
        self.assertFalse(facts("train")&facts("dev"))
    def test_every_negative_has_rationale(self):
        for r in self.data["train"]+self.data["dev"]:self.assertGreater(len(r["rationale"]),5)


if __name__=="__main__":unittest.main()
