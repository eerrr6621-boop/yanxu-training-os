"""Source-range protocol tests; no model or factual training data."""
import copy
import unittest
from worker import server_event_units, EVENT_SCOPE


class EventScopeTests(unittest.TestCase):
    def fixture(self):
        text = "🧪课程已结束，教学由本人独立完成。"
        scope = dict(event_id="event-0", source_start=0, source_end=len(text),
                     heading="", heading_start=0, heading_end=0, kind=EVENT_SCOPE,
                     offset_unit="unicode_code_point")
        document = dict(text=text, server_teaching_event_scopes=[scope])
        analysis = dict(complete=False, units=[], scoped_units=[], scoped_complete=False,
                        scoring_sections=[], review_reasons=["non_teaching_or_ambiguous_role"])
        return document, analysis

    def test_literal_scope_preserves_incomplete_remainder_and_no_score(self):
        doc, analysis = self.fixture()
        result = server_event_units(doc, analysis)
        self.assertFalse(result["complete"])
        self.assertEqual(result["review_reasons"], analysis["review_reasons"])
        self.assertEqual(result["scoped_units"][0]["text"], doc["text"])
        self.assertNotIn("similarity", result)
        self.assertEqual(analysis["scoped_units"], [])

    def test_no_contract_does_not_change_old_worker_path(self):
        _, analysis = self.fixture()
        self.assertIs(server_event_units(dict(text="任意文本"), analysis), analysis)

    def test_ranges_cannot_invent_or_split_units(self):
        for field, bad in [("source_start", -1), ("source_end", 1000), ("source_start", True),
                           ("source_start", 0.0), ("heading_start", 1), ("heading_end", 1),
                           ("kind", "verified_qualification"), ("offset_unit", "utf16"),
                           ("event_id", None), ("heading", "伪造标题")]:
            with self.subTest(field=field, bad=bad):
                doc, analysis = self.fixture()
                doc["server_teaching_event_scopes"][0][field] = bad
                with self.assertRaises(ValueError):
                    server_event_units(doc, analysis)

    def test_duplicates_are_rejected(self):
        doc, analysis = self.fixture()
        doc["server_teaching_event_scopes"] *= 2
        with self.assertRaises(ValueError):
            server_event_units(doc, analysis)

    def test_whole_source_denial_still_wins(self):
        doc, analysis = self.fixture()
        doc["text"] += "以上不是本人授课经历。"
        self.assertIs(server_event_units(doc, analysis), analysis)

    def test_already_complete_path_remains_complete_without_duplicate(self):
        doc, analysis = self.fixture()
        first = server_event_units(doc, analysis)
        analysis.update(complete=True, units=copy.deepcopy(first["scoped_units"]), review_reasons=[])
        result = server_event_units(doc, analysis)
        self.assertTrue(result["complete"])
        self.assertEqual(len(result["units"]), 1)


if __name__ == "__main__":
    unittest.main()
