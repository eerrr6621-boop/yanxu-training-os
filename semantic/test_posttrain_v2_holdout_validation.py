"""Pure structural checks; imports no trainer, model, old data or rules."""

from collections import Counter, defaultdict
import importlib.util
from pathlib import Path
import unittest


_SOURCE = Path(__file__).with_name("posttrain_v2_holdout.py")
_SPEC = importlib.util.spec_from_file_location("independent_sealed_holdout", _SOURCE)
_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)


class IndependentHoldoutValidation(unittest.TestCase):
    def setUp(self):
        self.rows = _MODULE.build_holdout()

    def test_schema_and_counts(self):
        self.assertEqual(len(self.rows), 40)
        self.assertEqual(len({row["id"] for row in self.rows}), 40)
        schema = {"id", "group", "family", "query", "pos", "neg", "rationale", "variant"}
        for row in self.rows:
            self.assertEqual(set(row), schema)
            for field in ("id", "group", "family", "query", "rationale", "variant"):
                self.assertIsInstance(row[field], str)
                self.assertTrue(row[field].strip())
            self.assertIsInstance(row["pos"], list)
            self.assertIsInstance(row["neg"], list)
            self.assertIn(len(row["pos"]), (0, 1))
            self.assertGreaterEqual(len(row["neg"]), 3)
            candidates = row["pos"] + row["neg"]
            self.assertEqual(len(candidates), len(set(candidates)))
            for text in candidates:
                self.assertIsInstance(text, str)
                self.assertTrue(text.strip())
        answerable = [row for row in self.rows if row["pos"]]
        rejection = [row for row in self.rows if not row["pos"]]
        self.assertEqual(len(answerable), 32)
        self.assertEqual(len(rejection), 8)
        self.assertEqual(sorted(Counter(row["group"] for row in answerable).values()), [8] * 4)
        self.assertEqual(sorted(Counter(row["group"] for row in rejection).values()), [2] * 4)

    def test_paired_families_keep_candidates_and_labels(self):
        families = defaultdict(list)
        for row in self.rows:
            families[row["family"]].append(row)
        self.assertEqual(len(families), 20)
        for members in families.values():
            self.assertEqual(len(members), 2)
            base, paraphrase = members
            self.assertEqual({row["variant"] for row in members}, {"base", "paraphrase"})
            self.assertNotEqual(base["query"], paraphrase["query"])
            for field in ("group", "family", "pos", "neg", "rationale"):
                self.assertEqual(base[field], paraphrase[field])

    def test_candidate_length_balance(self):
        # Surface-form checks cannot validate semantic labels, which were
        # independently authored and reviewed without seeing model results.
        bases = [row for row in self.rows if row["variant"] == "base"]
        positives = [len(text) for row in bases for text in row["pos"]]
        negatives = [len(text) for row in bases for text in row["neg"]]
        self.assertTrue(all(50 <= length <= 120 for length in positives + negatives))
        positive_mean = sum(positives) / len(positives)
        negative_mean = sum(negatives) / len(negatives)
        self.assertLess(abs(positive_mean - negative_mean), 15)
        answerable = [row for row in bases if row["pos"]]
        uniquely_longest = sum(
            len(row["pos"][0]) > max(map(len, row["neg"]))
            for row in answerable
        )
        self.assertLess(uniquely_longest, len(answerable) // 2)

    def test_return_values_are_independently_mutable(self):
        self.rows[0]["pos"][0] = "changed only in caller"
        self.rows[0]["neg"].append("caller-only candidate")
        fresh = _MODULE.build_holdout()
        self.assertNotEqual(self.rows[0]["pos"], fresh[0]["pos"])
        self.assertNotEqual(self.rows[0]["neg"], fresh[0]["neg"])
        self.assertEqual(fresh[0]["pos"], fresh[1]["pos"])
        self.assertEqual(len(fresh[0]["neg"]), 3)


if __name__ == "__main__":
    unittest.main()
