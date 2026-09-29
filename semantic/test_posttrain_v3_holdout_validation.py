"""Pure V3 holdout structure tests; no trainer, worker, old data or model."""

from collections import Counter, defaultdict
import importlib.util
from pathlib import Path
import unittest


_PATH = Path(__file__).with_name("posttrain_v3_holdout.py")
_SPEC = importlib.util.spec_from_file_location("sealed_v3_author_holdout", _PATH)
_DATA = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_DATA)


class V3HoldoutStructureTests(unittest.TestCase):
    def setUp(self):
        self.rows = _DATA.build_holdout()

    def test_return_schema_and_candidate_integrity(self):
        self.assertIsInstance(self.rows, list)
        schema = {"id", "group", "family", "query", "pos", "neg", "rationale", "variant"}
        self.assertEqual(len({row["id"] for row in self.rows}), len(self.rows))
        self.assertEqual(len({row["query"] for row in self.rows}), len(self.rows))
        for row in self.rows:
            self.assertEqual(set(row), schema)
            for name in schema - {"pos", "neg"}:
                self.assertIsInstance(row[name], str)
                self.assertTrue(row[name].strip())
            self.assertIsInstance(row["pos"], list)
            self.assertIsInstance(row["neg"], list)
            self.assertIn(len(row["pos"]), (0, 1))
            self.assertGreaterEqual(len(row["neg"]), 3)
            texts = row["pos"] + row["neg"]
            self.assertEqual(len(texts), len(set(texts)))
            for text in texts:
                self.assertIsInstance(text, str)
                self.assertTrue(text.strip())

    def test_answerable_and_rejection_denominators(self):
        answerable = [row for row in self.rows if row["pos"]]
        rejection = [row for row in self.rows if not row["pos"]]
        self.assertEqual(len(answerable), 32)
        self.assertEqual(len(rejection), 8)
        self.assertEqual(sorted(Counter(row["group"] for row in answerable).values()), [8] * 4)
        self.assertEqual(sorted(Counter(row["group"] for row in rejection).values()), [2] * 4)

    def test_twenty_paired_independent_fact_families(self):
        families = defaultdict(list)
        for row in self.rows:
            families[row["family"]].append(row)
        self.assertEqual(len(families), 20)
        self.assertEqual(sum(bool(rows[0]["pos"]) for rows in families.values()), 16)
        for rows in families.values():
            self.assertEqual(len(rows), 2)
            self.assertEqual({row["variant"] for row in rows}, {"base", "paraphrase"})
            self.assertNotEqual(rows[0]["query"], rows[1]["query"])
            for name in ("group", "family", "pos", "neg", "rationale"):
                self.assertEqual(rows[0][name], rows[1][name])

    def test_surface_lengths_do_not_always_identify_positive(self):
        bases = [row for row in self.rows if row["pos"] and row["variant"] == "base"]
        longest = sum(len(row["pos"][0]) > max(map(len, row["neg"])) for row in bases)
        shortest = sum(len(row["pos"][0]) < min(map(len, row["neg"])) for row in bases)
        self.assertGreaterEqual(longest, 3)
        self.assertGreaterEqual(shortest, 3)
        self.assertLess(longest, len(bases) * 0.75)
        self.assertLess(shortest, len(bases) * 0.75)
        all_lengths = [len(text) for row in self.rows for key in ("pos", "neg") for text in row[key]]
        self.assertTrue(all(35 <= size <= 220 for size in all_lengths))

    def test_positive_surface_forms_include_separate_sections_and_negation(self):
        # These are surface coverage checks, not semantic labelling heuristics.
        positives = [row["pos"][0] for row in self.rows if row["pos"] and row["variant"] == "base"]
        self.assertGreaterEqual(sum("\n\n" in text for text in positives), 3)
        self.assertGreaterEqual(sum("不" in text for text in positives), 6)

    def test_each_call_and_variant_are_independently_mutable(self):
        self.rows[0]["pos"][0] = "caller-only mutation"
        self.rows[0]["neg"].append("caller-only candidate")
        fresh = _DATA.build_holdout()
        self.assertNotEqual(self.rows[0]["pos"], fresh[0]["pos"])
        self.assertNotEqual(self.rows[0]["pos"], self.rows[1]["pos"])
        self.assertEqual(fresh[0]["pos"], fresh[1]["pos"])
        self.assertEqual(len(fresh[0]["neg"]), 3)


if __name__ == "__main__":
    unittest.main()
