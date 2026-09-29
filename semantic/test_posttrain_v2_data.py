"""Pure construction/label-design checks; no model, network, files, or training."""
from collections import Counter, defaultdict
import json
import unittest

from posttrain_v2_data import (
    DEV_TOPICS, TRAIN_TOPICS, KINDS, NEUTRAL_CONTEXT, _facts,
    audit_dataset, build_dataset, normalized,
)


def style_of(text):
    for prefix, style in (("讲师档案摘录：", 1), ("项目材料记录\n", 2),
                          ("经历栏目：", 4), ("资料核对节选\n", 5)):
        if text.startswith(prefix):
            return style
    return 3 if "\n以上为本条摘录。" in text else 0


class DatasetConstructionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = build_dataset()
        cls.audit = audit_dataset(cls.data)

    def test_fixed_counts_and_requested_schema(self):
        self.assertEqual(set(self.data), {"train", "dev", "notice"})
        self.assertEqual(len(self.data["train"]), 192)
        self.assertEqual(len(self.data["dev"]), 36)
        required = {"id", "group", "family", "query", "pos", "neg", "rationale", "variant"}
        for row in self.data["train"] + self.data["dev"]:
            self.assertEqual(set(row), required)
            self.assertEqual(len(row["pos"]), 1)
            self.assertEqual(len(row["neg"]), 4)
            self.assertTrue(all(isinstance(text, str) and text for text in row["pos"] + row["neg"]))

    def test_deterministic_rebuild(self):
        self.assertEqual(self.data, build_dataset())
        # Serialization is stable too; future freezing can hash this exact form.
        self.assertEqual(json.dumps(self.data, sort_keys=True), json.dumps(build_dataset(), sort_keys=True))

    def test_ids_and_queries_are_unique(self):
        rows = self.data["train"] + self.data["dev"]
        self.assertEqual(len({row["id"] for row in rows}), len(rows))
        self.assertEqual(len({normalized(row["query"]) for row in rows}), len(rows))
        self.assertEqual(self.audit["train"]["duplicate_queries"], [])
        self.assertEqual(self.audit["dev"]["duplicate_queries"], [])

    def test_topic_and_family_split_isolation(self):
        self.assertEqual(self.audit["shared_groups"], [])
        self.assertEqual(self.audit["shared_families"], [])
        self.assertEqual({row["group"] for row in self.data["train"]}, {topic[0] for topic in TRAIN_TOPICS})
        self.assertEqual({row["group"] for row in self.data["dev"]}, {topic[0] for topic in DEV_TOPICS})

    def test_every_family_keeps_all_six_variants_together(self):
        families = defaultdict(list)
        for split in ("train", "dev"):
            for row in self.data[split]:
                families[row["family"]].append((split, row["variant"]))
        self.assertEqual(len(families), 38)
        for members in families.values():
            self.assertEqual(len({split for split, _ in members}), 1)
            self.assertEqual(sorted(variant for _, variant in members), [f"wording_{i}" for i in range(1, 7)])

    def test_no_exact_passage_duplicates_or_cross_split_leakage(self):
        self.assertEqual(self.audit["cross_split_duplicate_passages"], [])
        self.assertEqual(self.audit["cross_family_duplicate_passages"], [])
        self.assertEqual(self.audit["within_family_duplicate_passages"], 0)
        for row in self.data["train"] + self.data["dev"]:
            passages = [normalized(text) for text in row["pos"] + row["neg"]]
            self.assertEqual(len(passages), len(set(passages)))

    def test_positive_short_middle_long_balanced_globally(self):
        self.assertEqual(self.audit["train"]["positive_length_ranks"], {0: 64, 2: 64, 4: 64})
        self.assertEqual(self.audit["dev"]["positive_length_ranks"], {0: 12, 2: 12, 4: 12})
        self.assertEqual(self.audit["train"]["length_tie_rows"], 0)
        self.assertEqual(self.audit["dev"]["length_tie_rows"], 0)

    def test_length_balanced_inside_every_family_not_only_whole_set(self):
        families = defaultdict(Counter)
        for row in self.data["train"] + self.data["dev"]:
            lengths = [len(text) for text in row["pos"] + row["neg"]]
            rank = sorted(range(5), key=lambda index: lengths[index]).index(0)
            families[row["family"]][rank] += 1
        for ranks in families.values():
            self.assertEqual(ranks, {0: 2, 2: 2, 4: 2})

    def test_single_length_heuristic_cannot_solve_the_dataset(self):
        for split in ("train", "dev"):
            for field in ("longest_passage_heuristic_correct", "shortest_passage_heuristic_correct", "middle_passage_heuristic_correct"):
                self.assertEqual(self.audit[split][field] * 3, self.audit[split]["queries"])

    def test_wording_variant_does_not_determine_length_bucket(self):
        for split in ("train", "dev"):
            ranks_by_wording = defaultdict(Counter)
            for row in self.data[split]:
                sizes = [len(text) for text in row["pos"] + row["neg"]]
                ranks_by_wording[row["variant"]][sorted(range(5), key=lambda i: sizes[i]).index(0)] += 1
            for counts in ranks_by_wording.values():
                self.assertEqual(set(counts), {0, 2, 4})
                self.assertLessEqual(max(counts.values()) - min(counts.values()), 1)

    def test_formatting_styles_appear_in_both_labels(self):
        positive, negative = Counter(), Counter()
        for row in self.data["train"]:
            positive.update(style_of(text) for text in row["pos"])
            negative.update(style_of(text) for text in row["neg"])
        self.assertEqual(set(positive), set(range(6)))
        self.assertEqual(set(negative), set(range(6)))
        for style in range(6):
            self.assertLess(abs(positive[style] / sum(positive.values()) - negative[style] / sum(negative.values())), .08)

    def test_compact_whole_sentences_with_no_padding_fragments(self):
        for row in self.data["train"] + self.data["dev"]:
            for text in row["pos"] + row["neg"]:
                self.assertLessEqual(len(text), 250)
                self.assertTrue(text.endswith("。"))
                self.assertNotIn(row["id"], text)
                self.assertNotIn(row["family"], text)

    def test_labels_have_fact_rationales_and_not_model_certification(self):
        for row in self.data["train"] + self.data["dev"]:
            self.assertGreater(len(row["rationale"]), 30)
        self.assertIn("AI-authored", self.data["notice"])
        self.assertIn("not", self.data["notice"])
        self.assertIn("business-expert", self.data["notice"])

    def test_author_defined_duration_boundary_is_consistent(self):
        for topic in TRAIN_TOPICS + DEV_TOPICS:
            positive, negatives, _ = _facts(topic, "completed_duration")
            self.assertIn("120分钟", positive)
            self.assertIn("已给", positive)
            self.assertIn("实际教学50分钟", negatives[0])
            self.assertIn("等待开课", negatives[1])
            self.assertIn("80分钟", negatives[2])
            self.assertIn("由合作讲师主讲", negatives[3])

    def test_all_train_families_include_role_completion_year_and_module_boundaries(self):
        for topic in TRAIN_TOPICS:
            families = {row["family"].split(":")[-1] for row in self.data["train"] if row["group"] == topic[0]}
            self.assertEqual(families, set(KINDS))

    def test_neutral_length_context_does_not_add_matching_facts(self):
        for sentence in NEUTRAL_CONTEXT:
            self.assertTrue(sentence.endswith("。"))
            self.assertLess(len(sentence), 18)
            self.assertFalse(any(word in sentence for word in ("分钟", "小时", "主讲", "本人", "连续三年", "参训", "培训", "已经讲完")))


if __name__ == "__main__":
    unittest.main()
