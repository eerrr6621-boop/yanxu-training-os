"""Metric regression tests using invented result rows, never evaluation data.

Only the summarize function is compiled from the evaluator's AST.  Its module
imports and main function are not executed: no encoder, model, trainer, holdout,
Java probe, network or external artifact is loaded by this test file.
"""

import ast
from pathlib import Path
import statistics
import unittest


def _load_summary():
    path = Path(__file__).with_name("evaluate_posttrain_v2.py")
    source = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    functions = [
        node for node in source.body
        if isinstance(node, ast.FunctionDef) and node.name == "summarize"
    ]
    if len(functions) != 1:
        raise ValueError("Expected exactly one metric summary function")
    isolated = ast.Module(body=functions, type_ignores=[])
    scope = {"statistics": statistics}
    exec(compile(isolated, str(path), "exec"), scope)
    return scope["summarize"]


_SUMMARIZE = _load_summary()


def _row(identifier, family="invented_pair", **changes):
    row = dict(
        id=identifier,
        family=family,
        positive_count=0,
        raw_top1_correct=False,
        raw_mrr=None,
        longest_heuristic_correct=False,
        worker_error=None,
        worker_top1=None,
        worker_top1_correct=False,
        worker_positive_present=False,
        worker_abstained=False,
        pipeline_admitted_ids=[],
        pipeline_wrongly_admitted_ids=[],
        pipeline_positive_admitted=False,
        pipeline_top1_correct=False,
        pipeline_no_admission=True,
        pipeline_has_review=False,
    )
    row.update(changes)
    return row


class PosttrainV2MetricTests(unittest.TestCase):
    def test_errors_are_not_stability_or_rejection_successes(self):
        rows = [
            _row(str(index), worker_error="invented failure", pipeline_has_review=True)
            for index in range(2)
        ]
        result = _SUMMARIZE(rows)
        self.assertEqual(result["worker_errors"], 2)
        self.assertEqual(result["worker_same_top1_family_count"], 0)
        self.assertEqual(result["worker_all_abstained_family_count"], 0)
        self.assertEqual(result["pipeline_no_match_error"], 2)
        self.assertEqual(result["pipeline_no_match_no_auto_admission"], 0)
        self.assertEqual(result["pipeline_no_match_rejected"], 0)
        self.assertEqual(result["pipeline_no_match_pending_review"], 0)

    def test_pending_review_is_not_pure_rejection(self):
        result = _SUMMARIZE([
            _row("a", pipeline_has_review=True),
            _row("b", pipeline_has_review=True),
        ])
        self.assertEqual(result["pipeline_no_match_no_auto_admission"], 2)
        self.assertEqual(result["pipeline_no_match_pending_review"], 2)
        self.assertEqual(result["pipeline_no_match_rejected"], 0)
        self.assertEqual(result["pipeline_no_match_error"], 0)

    def test_shared_abstention_is_separate_from_same_top1(self):
        result = _SUMMARIZE([
            _row("a", worker_abstained=True),
            _row("b", worker_abstained=True),
        ])
        self.assertEqual(result["worker_same_top1_family_count"], 0)
        self.assertEqual(result["worker_all_abstained_family_count"], 1)
        self.assertEqual(result["worker_no_match_abstained"], 2)
        self.assertEqual(result["pipeline_no_match_rejected"], 2)
        self.assertEqual(result["pipeline_no_match_pending_review"], 0)

    def test_same_non_null_top1_requires_successful_pair(self):
        result = _SUMMARIZE([
            _row("a", worker_top1=2),
            _row("b", worker_top1=2),
        ])
        self.assertEqual(result["paired_families"], 1)
        self.assertEqual(result["worker_same_top1_family_count"], 1)
        result = _SUMMARIZE([
            _row("a", worker_top1=2),
            _row("b", worker_top1=2, worker_error="invented failure"),
        ])
        self.assertEqual(result["worker_same_top1_family_count"], 0)

    def test_different_top1_and_singletons_are_not_stable_pairs(self):
        result = _SUMMARIZE([
            _row("a", worker_top1=1),
            _row("b", worker_top1=2),
            _row("c", family="invented_singleton", worker_top1=1),
        ])
        self.assertEqual(result["paired_families"], 1)
        self.assertEqual(result["worker_same_top1_family_count"], 0)

    def test_no_match_rows_are_excluded_from_positive_retrieval_metrics(self):
        # Deliberately inconsistent flags on a no-match row check denominator
        # isolation, not evaluator behavior or any real question's label.
        result = _SUMMARIZE([
            _row("p1", positive_count=1, raw_top1_correct=True, raw_mrr=1.0,
                 worker_top1_correct=True, longest_heuristic_correct=True),
            _row("p2", positive_count=1, raw_top1_correct=False, raw_mrr=0.5,
                 worker_top1_correct=False),
            _row("n1", family="invented_none", raw_top1_correct=True, raw_mrr=99.0,
                 worker_top1_correct=True, longest_heuristic_correct=True),
        ])
        self.assertEqual(result["cases"], 3)
        self.assertEqual(result["positive_cases"], 2)
        self.assertEqual(result["no_match_cases"], 1)
        self.assertEqual(result["raw_top1_correct"], 1)
        self.assertEqual(result["worker_top1_correct"], 1)
        self.assertEqual(result["longest_heuristic_correct"], 1)
        self.assertEqual(result["raw_mrr"], 0.75)
        self.assertEqual(result["raw_failed_ids"], ["p2"])
        self.assertEqual(result["worker_failed_ids"], ["p2"])

    def test_negative_admission_metric_counts_cases_not_candidates(self):
        result = _SUMMARIZE([
            _row("p", positive_count=1, raw_mrr=1.0,
                 pipeline_admitted_ids=[1, 2, 3], pipeline_no_admission=False,
                 pipeline_wrongly_admitted_ids=[2, 3], pipeline_positive_admitted=True),
            _row("n", family="invented_none", pipeline_admitted_ids=[1],
                 pipeline_wrongly_admitted_ids=[1], pipeline_no_admission=False),
        ])
        self.assertEqual(result["pipeline_negative_admission_cases"], 2)
        self.assertEqual(result["pipeline_negative_admission_ids"], ["p", "n"])
        self.assertEqual(result["pipeline_positive_admitted"], 1)
        self.assertEqual(result["pipeline_no_match_no_auto_admission"], 0)

    def test_missing_pipeline_result_is_not_a_rejection_success(self):
        row = _row("missing")
        for key in list(row):
            if key.startswith("pipeline_"):
                del row[key]
        result = _SUMMARIZE([row])
        self.assertEqual(result["pipeline_evaluated_cases"], 0)
        self.assertEqual(result["pipeline_no_match_no_auto_admission"], 0)
        self.assertEqual(result["pipeline_no_match_rejected"], 0)
        self.assertEqual(result["pipeline_no_match_pending_review"], 0)

    def test_no_match_outcomes_partition_non_admissions_without_errors(self):
        result = _SUMMARIZE([
            _row("reject"),
            _row("review", pipeline_has_review=True),
            _row("error", worker_error="invented failure"),
            _row("admit", pipeline_no_admission=False, pipeline_admitted_ids=[1],
                 pipeline_wrongly_admitted_ids=[1]),
        ])
        self.assertEqual(result["pipeline_no_match_rejected"], 1)
        self.assertEqual(result["pipeline_no_match_pending_review"], 1)
        self.assertEqual(result["pipeline_no_match_error"], 1)
        self.assertEqual(result["pipeline_no_match_no_auto_admission"], 2)
        self.assertEqual(result["pipeline_negative_admission_cases"], 1)

    def test_empty_input_has_no_invented_rates(self):
        result = _SUMMARIZE([])
        self.assertEqual(result["cases"], 0)
        self.assertEqual(result["positive_cases"], 0)
        self.assertEqual(result["no_match_cases"], 0)
        self.assertIsNone(result["raw_mrr"])
        self.assertIsNone(result["raw_median_ms"])


if __name__ == "__main__":
    unittest.main()
