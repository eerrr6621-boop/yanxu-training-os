"""Independent pure audit probes; never load training weights or held-out data."""
import ast
from pathlib import Path
import unittest

from evaluate_posttrain_v4 import attach_outcome, summarize


def entry(worker_error=None, raw_error=None):
    return dict(positive_ids=[1], worker_error=worker_error, raw_error=raw_error,
                raw_top1_correct=False, worker_top1_correct=False)


def outcome(admitted=()):
    return dict(admitted_ids=list(admitted), selected=[dict(teacher_id=i) for i in admitted],
                review=[], semantic_state=dict(status="inference_error"))


class IndependentV4AuditTest(unittest.TestCase):
    def test_all_reused_question_sources_are_in_training_freeze_inventory(self):
        # Source AST inspection only: no question module is imported or opened.
        root = Path(__file__).resolve().parent
        evaluator = ast.parse((root / "evaluate_posttrain_v4.py").read_text())
        reused = next(node for node in evaluator.body
                      if isinstance(node, ast.FunctionDef) and node.name == "reused_cases")
        names = {node.value for node in ast.walk(reused)
                 if isinstance(node, ast.Constant) and isinstance(node.value, str)
                 and node.value.endswith(".py")}
        trainer = ast.parse((root / "train_posttrain_v4.py").read_text())
        helper_assignment = next(node for node in ast.walk(trainer)
                                 if isinstance(node, ast.Assign)
                                 and any(isinstance(target, ast.Name) and target.id == "helpers"
                                         for target in node.targets))
        frozen = {node.value for node in ast.walk(helper_assignment)
                  if isinstance(node, ast.Constant) and isinstance(node.value, str)}
        self.assertTrue(names <= frozen, "Unfrozen reused sources: " + repr(sorted(names - frozen)))

    def test_empty_message_worker_exception_remains_counted(self):
        # str(TimeoutError()) is empty but it is still a failed inference.
        row = entry(worker_error=str(TimeoutError()))
        attach_outcome(row, outcome(), outcome((1,)))
        self.assertEqual(summarize([row])["worker_errors"], 1)

    def test_empty_message_worker_exception_keeps_separate_fallback(self):
        row = entry(worker_error=str(ValueError()))
        attach_outcome(row, outcome(), outcome((1,)))
        self.assertIsNotNone(row["rule_fallback"])
        self.assertFalse(row["pipeline_positive_admitted"])
        self.assertFalse(row["rule_fallback"]["counted_as_model_success"])

    def test_empty_message_raw_exception_remains_counted(self):
        row = entry(raw_error=str(TimeoutError()))
        attach_outcome(row, outcome(), outcome())
        self.assertEqual(summarize([row])["raw_errors"], 1)

    def test_none_is_the_only_success_sentinel(self):
        row = entry()
        attach_outcome(row, outcome((1,)), outcome((1,)))
        self.assertEqual(summarize([row])["worker_errors"], 0)
        self.assertEqual(summarize([row])["raw_errors"], 0)
        self.assertIsNone(row["rule_fallback"])
        self.assertTrue(row["pipeline_positive_admitted"])


if __name__ == "__main__":
    unittest.main()
