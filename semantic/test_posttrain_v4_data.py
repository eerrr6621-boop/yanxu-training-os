"""Data-only checks: no model or evaluation/holdout imports."""
import unittest
from collections import Counter
from posttrain_v4_data import build_dataset, validate, NEW
from posttrain_v3_data import build_dataset as previous_dataset


class ContinuationDataTest(unittest.TestCase):
    def test_declared_inventory(self):
        self.assertEqual(validate()["new_families"], 12)
        self.assertEqual(validate()["training_phrasings"], 144)

    def test_previous_training_facts_and_labels_are_unchanged(self):
        previous = previous_dataset()["train"]
        retained = build_dataset()["train"][:96]
        for old, new in zip(previous, retained):
            for key in ("query", "pos", "neg", "family", "factual_passages"):
                self.assertEqual(old[key], new[key])

    def test_family_variants_are_not_independent_facts(self):
        groups = {}
        for row in build_dataset()["train"]:
            groups.setdefault(row["family"], []).append(row)
        self.assertEqual(len(groups), 36)
        for family, rows in groups.items():
            self.assertEqual(len(rows), 4, family)
            self.assertEqual(len({tuple(r["factual_passages"]) for r in rows}), 1)

    def test_new_phrasings_are_unique_and_label_neutral_padding_keeps_facts(self):
        new = build_dataset()["train"][96:]
        self.assertEqual(len({r["query"] for r in new}), 48)
        for row in new:
            for original, padded in zip(row["factual_passages"], row["pos"] + row["neg"]):
                self.assertTrue(padded.startswith(original + "\n文档编排附录\n"))

    def test_no_old_dev_rows_or_families_enter_training(self):
        dev = previous_dataset()["dev"]
        train = build_dataset()["train"]
        self.assertFalse({r["family"] for r in dev} & {r["family"] for r in train})
        strings = lambda rows: {s for r in rows for s in [r["query"]] + r["pos"] + r["neg"]}
        self.assertFalse(strings(dev) & strings(train))

    def test_deterministic_and_no_validation_selection(self):
        self.assertEqual(build_dataset(), build_dataset())
        self.assertEqual(build_dataset()["dev"], [])
        self.assertEqual(len(NEW), 12)

    def test_new_core_fact_lengths_do_not_make_longest_always_correct(self):
        positions = Counter()
        for _, _, positive, negatives in NEW:
            p, n = len(positive), list(map(len, negatives))
            positions["shortest" if p < min(n) else "longest" if p > max(n) else "middle"] += 1
        self.assertEqual(positions, {"shortest": 4, "middle": 4, "longest": 4})


if __name__ == "__main__":
    unittest.main()
