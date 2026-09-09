"""Pure evaluator contracts, synthetic placeholders only; no held-out data load."""
import copy
import unittest
from evaluate_posttrain_v4 import attach_outcome, summarize, validate_cases


def outcome(admitted=(), selected=(), review=()):
    return dict(admitted_ids=list(admitted), selected=[dict(teacher_id=i) for i in selected],
                review=list(review), semantic_state=dict(status="inference_error"))


class MetricsTest(unittest.TestCase):
    def entry(self, error=None, positive=(1,)):
        return dict(positive_ids=list(positive), worker_error=error, raw_error=None,
                    raw_top1_correct=False, worker_top1_correct=False)

    def test_rule_fallback_is_not_a_successful_model_prediction(self):
        row = self.entry("complex_requirement_requires_review")
        attach_outcome(row, outcome(), outcome((1,), (1,)))
        self.assertFalse(row["pipeline_positive_admitted"])
        self.assertFalse(row["pipeline_top1_correct"])
        self.assertTrue(row["rule_fallback"]["positive_admitted"])
        summary = summarize([row])
        self.assertEqual(summary["worker_errors"], 1)
        self.assertEqual(summary["positive_admitted"], 0)
        self.assertEqual(summary["rule_fallback_positive_cases"], 1)

    def test_even_erroneous_adapter_admission_does_not_turn_error_into_model_success(self):
        row = self.entry("worker_failed")
        attach_outcome(row, outcome((1,), (1,)), outcome((1,), (1,)))
        self.assertFalse(row["pipeline_positive_admitted"])
        self.assertFalse(row["pipeline_top1_correct"])

    def test_fallback_wrong_admission_stays_visible(self):
        row = self.entry("worker_failed")
        attach_outcome(row, outcome(), outcome((2,), (2,)))
        self.assertEqual(row["rule_fallback"]["wrongly_admitted_ids"], [2])
        self.assertEqual(summarize([row])["rule_fallback_wrong_cases"], 1)

    def test_successful_output_reports_both_positive_and_wrong_admission(self):
        row = self.entry()
        attach_outcome(row, outcome((1, 2), (2, 1)), outcome())
        self.assertTrue(row["pipeline_positive_admitted"])
        self.assertEqual(row["pipeline_wrongly_admitted_ids"], [2])
        self.assertFalse(row["pipeline_top1_correct"])
        self.assertIsNone(row["rule_fallback"])

    def test_no_match_pending_review_is_not_reported_as_correct_rejection(self):
        row = self.entry(positive=())
        attach_outcome(row, outcome(review=[dict(teacher_id=2)]), outcome())
        summary = summarize([row])
        self.assertEqual(summary["positive_cases"], 0)
        self.assertEqual(summary["no_match_cases"], 1)
        self.assertEqual(summary["no_match_pending_review"], 1)

    def test_sealed_schema_validates_id_mapping_and_counts_without_real_questions(self):
        cases = [dict(id=f"synthetic_{i}", query=f"占位需求{i}",
                      documents=[dict(id=j, text=f"占位原文{i}-{j}") for j in range(1, 5)],
                      positive_ids=[2] if i < 16 else []) for i in range(20)]
        validate_cases(cases)
        for mutation in (lambda rows: rows[0].update(positive_ids=[5]),
                         lambda rows: rows[0].update(id=rows[1]["id"]),
                         lambda rows: rows[0]["documents"][0].update(id=2),
                         lambda rows: rows[0].update(positive_ids=[])):
            changed = copy.deepcopy(cases); mutation(changed)
            with self.assertRaises(ValueError):
                validate_cases(changed)


if __name__ == "__main__":
    unittest.main()
