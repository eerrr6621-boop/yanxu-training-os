"""Pure metadata/protocol tests: invented fixtures, no real model or question.

No holdout generator, training-data source, encoder or Java process is loaded.
Temporary files contain only invented metadata/bytes to exercise hash checks.
"""

from copy import deepcopy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_posttrain_v3 as evaluation
import export_posttrain_v3 as exporting


IDENTITY = {"model": "invented/test-artifact", "revision": "invented-revision", "precision": "int8"}


def _cases():
    return [{"id": "invented-case", "query": "invented requirement", "documents": [
        {"id": 1, "text": "invented document one"}, {"id": 2, "text": "invented document two"}]}]


def _replay_fixture(phase="prepare", error=False):
    cases = _cases()
    if phase == "evaluate":
        cases[0]["semantic_error" if error else "semantic_response"] = "invented protocol marker"
    state = dict(IDENTITY, candidate_count=2,
                 status="prepared" if phase == "prepare" else "inference_error" if error else "ready")
    rows = [{"id": cases[0]["id"], "focused_query": "invented requirement",
             "prepared_documents": deepcopy(cases[0]["documents"]), "semantic_state": state,
             "admitted_ids": [1], "excluded_ids": [2], "review": [], "selected": [{"teacher_id": 1}]}]
    return cases, rows


def _worker_fixture():
    return dict(IDENTITY, status="ready", complete=True, experimental_evaluation=True,
                results=[{"id": 1, "similarity": 0.6}, {"id": 2, "similarity": None}])


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


class V3ProtocolTests(unittest.TestCase):
    def test_module_import_does_not_load_models_or_questions(self):
        for name in ("worker", "evaluate_posttrain_pilot", "posttrain_v3_holdout", "posttrain_v3_data",
                     "posttrain_v2_holdout", "posttrain_holdout_cases", "torch", "onnxruntime"):
            self.assertNotIn(name, sys.modules)

    def test_valid_prepare_evaluate_and_error_replay(self):
        for phase, error in (("prepare", False), ("evaluate", False), ("evaluate", True)):
            with self.subTest(phase=phase, error=error):
                cases, rows = _replay_fixture(phase, error)
                evaluation.validate_replay(cases, rows, phase, IDENTITY)

    def test_replay_rejects_truncated_or_extra_cases(self):
        cases, rows = _replay_fixture()
        for output in ([], rows + deepcopy(rows)):
            with self.subTest(length=len(output)), self.assertRaises(ValueError):
                evaluation.validate_replay(cases, output, "prepare", IDENTITY)

    def test_replay_rejects_wrong_case_identity(self):
        cases, rows = _replay_fixture()
        rows[0]["id"] = "another invented case"
        with self.assertRaises(ValueError):
            evaluation.validate_replay(cases, rows, "prepare", IDENTITY)

    def test_replay_rejects_missing_duplicate_and_out_of_range_documents(self):
        for ids in ([1], [1, 1], [1, 3], [True, 2]):
            with self.subTest(ids=ids):
                cases, rows = _replay_fixture()
                rows[0]["prepared_documents"] = [{"id": item, "text": "invented"} for item in ids]
                with self.assertRaises(ValueError):
                    evaluation.validate_replay(cases, rows, "prepare", IDENTITY)

    def test_replay_rejects_identity_precision_status_and_count_mismatches(self):
        for field, value in (("model", "other"), ("revision", "other"), ("precision", "fp32"),
                             ("status", "ready"), ("candidate_count", 3)):
            with self.subTest(field=field):
                cases, rows = _replay_fixture()
                rows[0]["semantic_state"][field] = value
                with self.assertRaises(ValueError):
                    evaluation.validate_replay(cases, rows, "prepare", IDENTITY)

    def test_replay_requires_complete_disjoint_candidate_partition(self):
        mutations = [
            {"admitted_ids": []},
            {"excluded_ids": [1, 2]},
            {"review": [{"teacher_id": 2}]},
            {"selected": [{"teacher_id": 2}]},
            {"admitted_ids": [1, 3]},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                cases, rows = _replay_fixture()
                rows[0].update(mutation)
                with self.assertRaises(ValueError):
                    evaluation.validate_replay(cases, rows, "prepare", IDENTITY)

    def test_worker_accepts_finite_scores_and_null_evidence(self):
        evaluation.validate_worker_response(_worker_fixture(), {1, 2}, IDENTITY)

    def test_worker_rejects_missing_duplicate_unknown_and_boolean_ids(self):
        for ids in ([1], [1, 1], [1, 3], [True, 2]):
            with self.subTest(ids=ids):
                response = _worker_fixture()
                response["results"] = [{"id": item, "similarity": 0.3} for item in ids]
                with self.assertRaises(ValueError):
                    evaluation.validate_worker_response(response, {1, 2}, IDENTITY)

    def test_worker_rejects_non_finite_out_of_range_and_non_numeric_scores(self):
        for score in (float("nan"), float("inf"), -1.1, 1.1, True, "0.5"):
            with self.subTest(score=score):
                response = _worker_fixture()
                response["results"][0]["similarity"] = score
                with self.assertRaises(ValueError):
                    evaluation.validate_worker_response(response, {1, 2}, IDENTITY)

    def test_worker_rejects_identity_and_completion_changes(self):
        for field, value in (("model", "other"), ("revision", "other"), ("precision", "fp32"),
                             ("status", "partial"), ("complete", False), ("experimental_evaluation", False)):
            with self.subTest(field=field):
                response = _worker_fixture()
                response[field] = value
                with self.assertRaises(ValueError):
                    evaluation.validate_worker_response(response, {1, 2}, IDENTITY)

    def test_factual_passages_enter_normalized_overlap_set(self):
        rows = [{"query": "Ａ b", "pos": ["Pos"], "neg": ["Neg"],
                 "factual_passages": {"pos": ["Raw Core"], "neg": ["raw alternative"]}}]
        self.assertEqual(evaluation.passage_set(rows), {"ab", "pos", "neg", "rawcore", "rawalternative"})


class V3FilesystemFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="v3-pure-validation-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def _classpath(self):
        classes = self.root / "classes"
        probe = classes / "com/training/PosttrainingV2Probe.class"
        probe.parent.mkdir(parents=True)
        probe.write_bytes(b"invented class bytes, not executable")
        jar = self.root / "library.jar"
        jar.write_bytes(b"invented jar bytes, not executable")
        return os.pathsep.join((str(classes), str(jar))), probe, jar

    def _training(self):
        repo, training = self.root / "repo", self.root / "training"
        semantic = repo / "semantic"
        semantic.mkdir(parents=True)
        helpers = {}
        for name in evaluation.REQUIRED_HELPERS:
            path = semantic / name
            path.write_text("# invented opaque fixture\n", encoding="utf-8")
            helpers[name] = evaluation.sha(path)
        sources = {}
        for name in ("src/com/training/Invented.java", "scripts/PosttrainingV2Probe.java", "semantic/worker.py"):
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"invented source fixture")
            sources[name] = evaluation.sha(path)
        generator_hashes = {}
        for name, field in (("train_posttrain_v3.py", "training_source_sha256"),
                            ("posttrain_v3_data.py", "data_generator_sha256"),
                            ("posttrain_v3_holdout.py", "holdout_generator_sha256")):
            path = semantic / name
            path.write_bytes(b"invented opaque bytes, never imported")
            generator_hashes[field] = evaluation.sha(path)
        checkpoint = training / "checkpoint"
        checkpoint.mkdir(parents=True)
        (checkpoint / "model.safetensors").write_bytes(b"invented non-model bytes")
        _write_json(checkpoint / "config.json", {"invented": "configuration"})
        tokenizer_hashes = {}
        for name in evaluation.TOKENIZER_FILES:
            _write_json(checkpoint / name, {"invented": name})
            tokenizer_hashes[name] = evaluation.sha(checkpoint / name)
        _write_json(training / "data-snapshot.json", {"fixture": "opaque invented data"})
        _write_json(training / "ancestor-data-snapshot.json", {"fixture": "opaque invented ancestor"})
        config = {"epochs": 2, "learning_rate": 1e-6, "accumulation": 4, "trainable_prefix": "encoder.layer.3."}
        common = {"experiment": evaluation.EXPERIMENT, "config": config,
                  "parent_checkpoint_sha256": "a" * 64, "parent_checkpoint_config_sha256": "b" * 64,
                  "trainable_parameter_prefix": "encoder.layer.3."}
        report = dict(common, completed=True, trained=True, deployed=False, examples=8, optimizer_updates=4,
                      checkpoint_sha256=evaluation.sha(checkpoint / "model.safetensors"),
                      checkpoint_config_sha256=evaluation.sha(checkpoint / "config.json"),
                      checkpoint_tokenizer_sha256=tokenizer_hashes)
        freeze = dict(common, expected_optimizer_updates=4, helper_sources_sha256=helpers,
                      production_sources_sha256=sources,
                      training_data_sha256=evaluation.sha(training / "data-snapshot.json"),
                      ancestor_training_data_sha256=evaluation.sha(training / "ancestor-data-snapshot.json"),
                      **generator_hashes)
        _write_json(training / "training-report.json", report)
        _write_json(training / "freeze.json", freeze)
        return repo, training, report, freeze

    def test_classpath_inventory_is_ordered_and_detects_byte_changes(self):
        classpath, probe, jar = self._classpath()
        before = evaluation.capture_classpath(classpath)
        self.assertEqual([entry["kind"] for entry in before], ["directory", "file"])
        probe.write_bytes(b"different invented class bytes")
        self.assertNotEqual(before, evaluation.capture_classpath(classpath))
        changed = evaluation.capture_classpath(classpath)
        jar.write_bytes(b"different invented jar bytes")
        self.assertNotEqual(changed, evaluation.capture_classpath(classpath))

    def test_classpath_rejects_empty_wildcard_and_wrong_first_entry(self):
        classpath, _, jar = self._classpath()
        for value in ("", classpath + os.pathsep, str(jar), str(self.root / "*")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                evaluation.capture_classpath(value)

    def test_classpath_detects_added_shadow_class(self):
        classpath, probe, _ = self._classpath()
        before = evaluation.capture_classpath(classpath)
        (probe.parent / "Shadow.class").write_bytes(b"invented shadow")
        self.assertNotEqual(before, evaluation.capture_classpath(classpath))

    def test_final_training_metadata_accepts_complete_consistent_fixture(self):
        repo, training, report, freeze = self._training()
        self.assertEqual(evaluation.verify_frozen_training(training, repo), (report, freeze))

    def test_final_training_rejects_wrong_experiment(self):
        repo, training, report, _ = self._training()
        report["experiment"] = "other-experiment"
        _write_json(training / "training-report.json", report)
        with self.assertRaises(ValueError):
            evaluation.verify_frozen_training(training, repo)

    def test_final_training_rejects_non_final_update_count(self):
        repo, training, report, _ = self._training()
        report["optimizer_updates"] = 3
        _write_json(training / "training-report.json", report)
        with self.assertRaises(ValueError):
            evaluation.verify_frozen_training(training, repo)

    def test_final_training_rejects_lower_layer_training(self):
        repo, training, report, freeze = self._training()
        report["config"]["trainable_prefix"] = "encoder.layer.0."
        freeze["config"]["trainable_prefix"] = "encoder.layer.0."
        _write_json(training / "training-report.json", report)
        _write_json(training / "freeze.json", freeze)
        with self.assertRaises(ValueError):
            evaluation.verify_frozen_training(training, repo)

    def test_final_training_rejects_weight_config_and_tokenizer_mutations(self):
        repo, training, _, _ = self._training()
        checkpoint = training / "checkpoint"
        for name in ("model.safetensors", "config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"):
            with self.subTest(name=name):
                path = checkpoint / name
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                with self.assertRaises(ValueError):
                    evaluation.verify_frozen_training(training, repo)
                path.write_bytes(original)

    def test_final_training_rejects_unfrozen_helper_and_generator(self):
        repo, training, _, _ = self._training()
        for name in ("evaluate_posttrain_v2.py", "evaluate_posttrain_pilot.py", "posttrain_v3_holdout.py"):
            with self.subTest(name=name):
                path = repo / "semantic" / name
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                with self.assertRaises(ValueError):
                    evaluation.verify_frozen_training(training, repo)
                path.write_bytes(original)

    def test_final_training_rejects_ancestor_and_factual_snapshot_changes(self):
        repo, training, _, _ = self._training()
        for name in ("data-snapshot.json", "ancestor-data-snapshot.json"):
            with self.subTest(name=name):
                path = training / name
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                with self.assertRaises(ValueError):
                    evaluation.verify_frozen_training(training, repo)
                path.write_bytes(original)

    def test_tokenizer_semantics_may_reserialize_but_not_change_vocabulary(self):
        checkpoint = self.root / "checkpoint"
        base_path = self.root / "base-tokenizer.json"
        base = {"model": {"vocab": {"invented": 1}}, "normalizer": {"type": "NFKC"}}
        _write_json(base_path, base)
        _write_json(checkpoint / "tokenizer.json", dict(base, padding=None))
        evaluation.verify_tokenizer_semantics(checkpoint, base_path)
        _write_json(checkpoint / "tokenizer.json", {"model": {"vocab": {"invented": 2}}})
        with self.assertRaises(ValueError):
            evaluation.verify_tokenizer_semantics(checkpoint, base_path)

    def _artifact(self):
        repo, training, report, frozen = self._training()
        model_dir = self.root / "artifact"
        model_dir.mkdir()
        (model_dir / "model-int8.onnx").write_bytes(b"invented onnx bytes, never loaded")
        (model_dir / "tokenizer.json").write_bytes((training / "checkpoint/tokenizer.json").read_bytes())
        hashes = {name: evaluation.sha(model_dir / name) for name in ("model-int8.onnx", "tokenizer.json")}
        frozen["base_manifest"] = {"model": "invented/base", "revision": "invented-base", "sha256": hashes}
        _write_json(training / "freeze.json", frozen)
        manifest = {
            "schema_version": "yanxu-experimental-embedding-v1", "model": evaluation.V3_MODEL,
            "revision": "v3-" + report["checkpoint_sha256"][:16], "experiment": evaluation.EXPERIMENT,
            "base_model": "invented/base", "base_revision": "invented-base", "production_approved": False,
            "dimensions": 512, "pooling": "CLS+L2", "max_tokens": 512, "sha256": hashes,
            "trained_weights_sha256": report["checkpoint_sha256"], "training_config": report["config"],
            "checkpoint_config_sha256": report["checkpoint_config_sha256"],
            "checkpoint_tokenizer_sha256": report["checkpoint_tokenizer_sha256"],
            "parent_checkpoint_sha256": report["parent_checkpoint_sha256"],
            "parent_checkpoint_config_sha256": report["parent_checkpoint_config_sha256"],
            "freeze_sha256": evaluation.sha(training / "freeze.json"),
            "training_report_sha256": evaluation.sha(training / "training-report.json"),
        }
        _write_json(model_dir / "manifest.json", manifest)
        return training, report, frozen, model_dir, manifest

    def test_artifact_validation_accepts_bound_v3_identity(self):
        training, report, frozen, model_dir, manifest = self._artifact()
        checked, checked_report = evaluation.verify_artifact(model_dir, "int8", training, report, frozen)
        self.assertEqual(checked, manifest)
        self.assertEqual(checked_report, report)

    def test_artifact_validation_rejects_identity_config_and_lineage_changes(self):
        training, report, frozen, model_dir, manifest = self._artifact()
        for field, value in (("revision", "pilot-fake"), ("model", "invented/base"),
                             ("trained_weights_sha256", "f" * 64), ("production_approved", True),
                             ("parent_checkpoint_sha256", "f" * 64),
                             ("parent_checkpoint_config_sha256", "f" * 64),
                             ("checkpoint_config_sha256", "f" * 64),
                             ("freeze_sha256", "f" * 64), ("training_report_sha256", "f" * 64)):
            with self.subTest(field=field):
                changed = dict(manifest)
                changed[field] = value
                _write_json(model_dir / "manifest.json", changed)
                with self.assertRaises(ValueError):
                    evaluation.verify_artifact(model_dir, "int8", training, report, frozen)
        _write_json(model_dir / "manifest.json", manifest)

    def test_artifact_validation_rejects_changed_onnx_bytes(self):
        training, report, frozen, model_dir, _ = self._artifact()
        (model_dir / "model-int8.onnx").write_bytes(b"tampered invented artifact")
        with self.assertRaises(ValueError):
            evaluation.verify_artifact(model_dir, "int8", training, report, frozen)


class V3ExportIdentityTests(unittest.TestCase):
    def test_manifest_identity_is_v3_from_construction(self):
        training = {"experiment": evaluation.EXPERIMENT, "checkpoint_sha256": "1" * 64,
                    "checkpoint_config_sha256": "2" * 64, "checkpoint_tokenizer_sha256": {},
                    "parent_checkpoint_sha256": "3" * 64, "parent_checkpoint_config_sha256": "4" * 64,
                    "parameter_count": 123, "config": {"invented": True}}
        frozen = {"base_manifest": {"model": "invented/base", "revision": "invented-base-revision",
                                     "sha256": {"tokenizer.json": "5" * 64}}, "training_data_sha256": "6" * 64}
        hashes = {"model-fp32.onnx": "7" * 64, "model-int8.onnx": "8" * 64, "tokenizer.json": "5" * 64}
        manifest = exporting.build_manifest(training, frozen, hashes, {name: 1 for name in hashes})
        self.assertEqual(manifest["model"], "yanxu/bge-small-zh-anchored-v3")
        self.assertEqual(manifest["revision"], "v3-" + "1" * 16)
        self.assertFalse(manifest["production_approved"])
        self.assertEqual(manifest["trained_weights_sha256"], training["checkpoint_sha256"])
        self.assertEqual(manifest["parent_checkpoint_sha256"], training["parent_checkpoint_sha256"])
        training["experiment"] = "another-experiment"
        with self.assertRaises(ValueError):
            exporting.build_manifest(training, frozen, hashes, {name: 1 for name in hashes})


if __name__ == "__main__":
    unittest.main()
