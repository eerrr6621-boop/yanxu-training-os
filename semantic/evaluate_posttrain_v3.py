"""Frozen V3 offline evaluation; imports no questions or models at module load.

V3 is author-created synthetic testing, not a real-world blind benchmark.
The final checkpoint, source dependencies and executed classpath are verified
before any held-out generator is loaded.  Reused tests are diagnostic only.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import importlib.util
import json
import math
import os
from pathlib import Path
import random
import resource
import sys
import time
import unicodedata


os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                  HF_HUB_DISABLE_TELEMETRY="1", TOKENIZERS_PARALLELISM="false")
EXPERIMENT = "anchored-top-layer-v3"
V3_MODEL = "yanxu/bge-small-zh-anchored-v3"
TOKENIZER_FILES = {"tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"}
REQUIRED_HELPERS = {
    "evaluate_posttrain_v3.py", "export_posttrain_v3.py", "evaluate_posttrain_v2.py",
    "evaluate_posttrain_pilot.py", "export_posttrain_pilot.py", "train_posttrain_pilot.py",
    "posttrain_v2_holdout.py", "posttrain_holdout_cases.py", "test_posttrain_v3_pipeline_validation.py",
    "test_posttrain_v3_holdout_validation.py",
}


def require(condition, reason):
    if not condition:
        raise ValueError("v3_audit:" + reason)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def capture_classpath(classpath):
    """Return an ordered, relocatable digest of every classpath entry's bytes.

    Safe for the trainer to call before freezing: no Java execution, question
    import or model load.  Empty entries/wildcards/symlinked directory contents
    are refused so runtime class resolution cannot escape the digest inventory.
    """
    entries = classpath.split(os.pathsep)
    require(entries and all(entries), "empty_classpath_entry")
    result = []
    for entry in entries:
        require("*" not in entry, "classpath_wildcard")
        path = Path(entry).resolve()
        if path.is_dir():
            members = sorted(path.rglob("*"))
            require(not any(member.is_symlink() for member in members), "classpath_internal_symlink")
            files = {member.relative_to(path).as_posix(): sha(member)
                     for member in members if member.is_file()}
            require(bool(files), "empty_classpath_directory")
            result.append({"kind": "directory", "files": files})
        else:
            require(path.is_file(), "classpath_entry_missing")
            result.append({"kind": "file", "sha256": sha(path)})
    require(result[0]["kind"] == "directory" and
            "com/training/PosttrainingV2Probe.class" in result[0]["files"], "probe_not_in_first_classpath_entry")
    return result


def _safe_relative(root, name):
    path = (root / name).resolve()
    require(not Path(name).is_absolute() and path.is_relative_to(root.resolve()), "source_path_escape")
    return path


def verify_frozen_sources(repo, frozen):
    helpers = frozen["helper_sources_sha256"]
    require(REQUIRED_HELPERS <= set(helpers), "missing_helper_freeze")
    for name, expected in helpers.items():
        require(sha(_safe_relative(repo / "semantic", name)) == expected, "helper_changed:" + name)
    production = frozen["production_sources_sha256"]
    java_names = {path.relative_to(repo).as_posix() for path in (repo / "src/com/training").glob("*.java")}
    required = java_names | {"semantic/worker.py", "scripts/PosttrainingV2Probe.java"}
    require(required <= set(production), "missing_production_source_freeze")
    for name, expected in production.items():
        require(sha(_safe_relative(repo, name)) == expected, "production_source_changed:" + name)
    for name, field in (("train_posttrain_v3.py", "training_source_sha256"),
                        ("posttrain_v3_data.py", "data_generator_sha256"),
                        ("posttrain_v3_holdout.py", "holdout_generator_sha256")):
        require(sha(repo / "semantic" / name) == frozen[field], "generator_changed:" + name)


def verify_frozen_training(training_dir, repo):
    """Validate final lineage and opaque data hashes without loading questions."""
    trained = read_json(training_dir / "training-report.json")
    frozen = read_json(training_dir / "freeze.json")
    require(trained.get("experiment") == EXPERIMENT and frozen.get("experiment") == EXPERIMENT,
            "wrong_experiment")
    require(trained.get("completed") is True and trained.get("trained") is True and
            trained.get("deployed") is False, "training_not_complete_or_deployed")
    config = trained["config"]
    require(config == frozen["config"], "training_config_changed")
    require(config.get("epochs") == 2 and config.get("learning_rate") == 1e-6,
            "not_prespecified_v3_schedule")
    examples, accumulation = trained["examples"], config["accumulation"]
    require(type(examples) is int and examples > 0 and type(accumulation) is int and
            accumulation > 0 and examples % accumulation == 0, "invalid_update_denominator")
    expected_updates = config["epochs"] * examples // accumulation
    require(trained["optimizer_updates"] == frozen["expected_optimizer_updates"] == expected_updates,
            "not_fixed_final_step")
    require(config.get("trainable_prefix") == "encoder.layer.3." and
            frozen.get("trainable_parameter_prefix") == trained.get("trainable_parameter_prefix") ==
            "encoder.layer.3.", "not_top_layer_only")
    if "trainable_parameter_names" in frozen:
        names = frozen["trainable_parameter_names"]
        require(isinstance(names, list) and bool(names) and len(set(names)) == len(names) and
                all(isinstance(name, str) and name.startswith("encoder.layer.3.") for name in names),
                "invalid_trainable_parameter_inventory")
        require(trained.get("trainable_parameter_names") == names, "trainable_parameters_changed")
    for field in ("parent_checkpoint_sha256", "parent_checkpoint_config_sha256"):
        require(trained[field] == frozen[field], "parent_lineage_changed:" + field)
    checkpoint = training_dir / "checkpoint"
    require(sha(checkpoint / "model.safetensors") == trained["checkpoint_sha256"], "checkpoint_weights_changed")
    require(sha(checkpoint / "config.json") == trained["checkpoint_config_sha256"], "checkpoint_config_changed")
    tokenizer_hashes = trained["checkpoint_tokenizer_sha256"]
    require(isinstance(tokenizer_hashes, dict) and set(tokenizer_hashes) == TOKENIZER_FILES,
            "tokenizer_inventory_incomplete")
    for name, expected in tokenizer_hashes.items():
        require(sha(checkpoint / name) == expected, "checkpoint_tokenizer_changed:" + name)
    require(sha(training_dir / "data-snapshot.json") == frozen["training_data_sha256"], "training_data_changed")
    require(sha(training_dir / "ancestor-data-snapshot.json") == frozen["ancestor_training_data_sha256"],
            "ancestor_data_changed")
    verify_frozen_sources(repo, frozen)
    return trained, frozen


def verify_tokenizer_semantics(checkpoint, base_tokenizer_path):
    trained = read_json(checkpoint / "tokenizer.json")
    base = read_json(base_tokenizer_path)
    for field in ("model", "normalizer", "pre_tokenizer", "post_processor", "added_tokens"):
        require(trained.get(field) == base.get(field), "tokenizer_semantics_changed:" + field)


def verify_artifact(model_dir, precision, artifact_training_dir, trained, frozen):
    """Bind the actual ONNX, tokenizer and checkpoint to an honest identity."""
    manifest = read_json(model_dir / "manifest.json")
    base = frozen["base_manifest"]
    require(manifest.get("dimensions") == 512 and manifest.get("pooling") == "CLS+L2" and
            manifest.get("max_tokens") == 512, "unsupported_embedding_contract")
    require(precision in {"fp32", "int8"}, "unsupported_precision")
    for name in (f"model-{precision}.onnx", "tokenizer.json"):
        require(sha(model_dir / name) == manifest["sha256"][name], "artifact_bytes_changed:" + name)
    require(manifest["sha256"]["tokenizer.json"] == base["sha256"]["tokenizer.json"], "base_tokenizer_mismatch")
    if manifest.get("schema_version") != "yanxu-experimental-embedding-v1":
        require(manifest["model"] == base["model"] and manifest["revision"] == base["revision"],
                "unrecognized_base_identity")
        require(manifest["sha256"][f"model-{precision}.onnx"] == base["sha256"][f"model-{precision}.onnx"],
                "base_weights_not_frozen")
        return manifest, trained
    require(artifact_training_dir is not None, "experimental_artifact_requires_training_directory")
    artifact = read_json(artifact_training_dir / "training-report.json")
    require(artifact.get("completed") is True and artifact.get("trained") is True and
            artifact.get("deployed") is False, "artifact_training_incomplete")
    checkpoint = artifact_training_dir / "checkpoint"
    require(sha(checkpoint / "model.safetensors") == artifact["checkpoint_sha256"] ==
            manifest["trained_weights_sha256"], "artifact_checkpoint_mismatch")
    actual_config = sha(checkpoint / "config.json")
    if "checkpoint_config_sha256" in artifact:
        require(actual_config == artifact["checkpoint_config_sha256"], "artifact_configuration_changed")
    elif artifact["checkpoint_sha256"] == frozen["parent_checkpoint_sha256"]:
        require(actual_config == frozen["parent_checkpoint_config_sha256"], "parent_configuration_changed")
    else:
        require(False, "unfrozen_artifact_configuration")
    prefixes = {"yanxu/bge-small-zh-synthetic-pilot": "pilot-",
                "yanxu/bge-small-zh-augmented-v2": "v2-", V3_MODEL: "v3-"}
    require(manifest.get("model") in prefixes, "unrecognized_experimental_identity")
    require(manifest["revision"] == prefixes[manifest["model"]] + artifact["checkpoint_sha256"][:16],
            "revision_not_bound_to_weights")
    require(manifest.get("production_approved") is False and manifest.get("base_model") == base["model"] and
            manifest.get("base_revision") == base["revision"], "experimental_base_mismatch")
    require(manifest.get("training_config") == artifact["config"], "artifact_training_config_mismatch")
    verify_tokenizer_semantics(checkpoint, model_dir / "tokenizer.json")
    if manifest["model"] == "yanxu/bge-small-zh-synthetic-pilot":
        require(artifact["checkpoint_sha256"] == frozen["parent_checkpoint_sha256"], "not_frozen_v1_parent")
    if manifest["model"] == "yanxu/bge-small-zh-augmented-v2":
        require(artifact.get("experiment") == "balanced-augmentation-v2", "wrong_v2_artifact_experiment")
    if manifest["model"] == V3_MODEL:
        require(artifact["checkpoint_sha256"] == trained["checkpoint_sha256"] and
                artifact.get("experiment") == manifest.get("experiment") == EXPERIMENT, "wrong_v3_checkpoint")
        require(manifest.get("parent_checkpoint_sha256") == frozen["parent_checkpoint_sha256"] and
                manifest.get("parent_checkpoint_config_sha256") == frozen["parent_checkpoint_config_sha256"] and
                manifest.get("checkpoint_config_sha256") == actual_config and
                manifest.get("checkpoint_tokenizer_sha256") == trained["checkpoint_tokenizer_sha256"],
                "v3_export_lineage_mismatch")
        require(manifest.get("freeze_sha256") == sha(artifact_training_dir / "freeze.json") and
                manifest.get("training_report_sha256") == sha(artifact_training_dir / "training-report.json"),
                "v3_export_metadata_changed")
    return manifest, artifact


def _ids(values, expected, reason):
    require(isinstance(values, list) and all(type(value) is int for value in values), reason + ":id_type")
    require(len(values) == len(set(values)) and set(values) <= expected, reason + ":id_range")
    return set(values)


def validate_replay(cases, output, phase, identity):
    require(isinstance(output, list) and len(output) == len(cases), "replay_case_count")
    require([row.get("id") for row in output] == [row["id"] for row in cases], "replay_case_order")
    for case, row in zip(cases, output):
        expected = {document["id"] for document in case["documents"]}
        require(expected == set(range(1, len(case["documents"]) + 1)), "input_candidate_id_range")
        prepared = row.get("prepared_documents")
        require(isinstance(prepared, list), "prepared_documents_missing")
        prepared_ids = _ids([document.get("id") for document in prepared], expected, "prepared")
        require(prepared_ids == expected and all(isinstance(document.get("text"), str) for document in prepared),
                "prepared_candidates_incomplete")
        require(isinstance(row.get("focused_query"), str) and row["focused_query"].strip(), "empty_focused_query")
        state = row["semantic_state"]
        require(all(state.get(key) == value for key, value in identity.items()), "replay_identity_mismatch")
        require(state.get("candidate_count") == len(expected), "replay_candidate_count")
        require(state.get("status") == ("prepared" if phase == "prepare" else
                "inference_error" if "semantic_error" in case else "ready"), "replay_status_mismatch")
        admitted = _ids(row.get("admitted_ids"), expected, "admitted")
        excluded = _ids(row.get("excluded_ids"), expected, "excluded")
        review = _ids([candidate.get("teacher_id") for candidate in row.get("review", [])], expected, "review")
        selected = _ids([candidate.get("teacher_id") for candidate in row.get("selected", [])], expected, "selected")
        require(not (admitted & excluded or admitted & review or excluded & review) and
                admitted | excluded | review == expected, "candidate_partition_incomplete")
        require(selected <= admitted, "selected_candidate_not_admitted")


def validate_worker_response(response, expected, identity):
    require(isinstance(response, dict) and response.get("status") == "ready" and
            response.get("complete") is True and response.get("experimental_evaluation") is True,
            "worker_response_incomplete")
    require(all(response.get(key) == value for key, value in identity.items()), "worker_identity_mismatch")
    results = response.get("results")
    require(isinstance(results, list), "worker_results_missing")
    require(_ids([row.get("id") for row in results], expected, "worker") == expected, "worker_candidates_incomplete")
    for row in results:
        require("similarity" in row, "worker_score_missing")
        score = row["similarity"]
        require(score is None or type(score) in (int, float) and math.isfinite(score) and -1 <= score <= 1,
                "worker_score_invalid")


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)


def passage_set(rows):
    result = set()
    for row in rows:
        for text in [row["query"]] + row["pos"] + row["neg"] + list(_strings(row.get("factual_passages", []))):
            result.add("".join(unicodedata.normalize("NFKC", text).casefold().split()))
    return result


def _load_holdout(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    rows = module.build_holdout()
    require(isinstance(rows, list), "holdout_not_list")
    return rows


def _helpers(repo):
    v2 = importlib.import_module("evaluate_posttrain_v2")
    pilot = importlib.import_module("evaluate_posttrain_pilot")
    worker = importlib.import_module("worker")
    for module in (v2, pilot, worker):
        require(Path(module.__file__).resolve() == (repo / "semantic" / (module.__name__ + ".py")).resolve(),
                "helper_import_path_mismatch")
    return v2.replay, v2.summarize, pilot.EvaluationEncoder


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--v3-training-dir", type=Path, required=True)
    parser.add_argument("--artifact-training-dir", type=Path)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--precision", choices=("int8", "fp32"), default="int8")
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    require(not output.is_relative_to(repo) and not output.exists() and
            not any(part in {"web", "public", "static"} for part in output.parts), "new_private_report_required")
    trained, frozen = verify_frozen_training(args.v3_training_dir, repo)
    frozen_bytes = sha(args.v3_training_dir / "freeze.json")
    training_report_bytes = sha(args.v3_training_dir / "training-report.json")
    classes = capture_classpath(args.classpath)
    require(classes == frozen["evaluation_classpath_sha256"], "executed_classpath_not_frozen")
    artifact_dir = args.artifact_training_dir or args.v3_training_dir
    manifest, artifact = verify_artifact(args.model_dir, args.precision, artifact_dir, trained, frozen)
    replay, summarize, Encoder = _helpers(repo)
    started = time.monotonic()
    encoder = Encoder(args.model_dir, args.precision, artifact)
    startup = time.monotonic() - started
    identity = dict(model=encoder.actual_model, revision=encoder.actual_revision, precision=args.precision)
    require(identity["model"] == manifest["model"] and identity["revision"] == manifest["revision"] and
            encoder.version == manifest["sha256"][f"model-{args.precision}.onnx"], "loaded_artifact_identity_mismatch")
    # First data/question execution occurs only after all final artifacts and the
    # exact inference/replay dependency chain above have passed their checks.
    data = read_json(args.v3_training_dir / "data-snapshot.json")
    ancestor = read_json(args.v3_training_dir / "ancestor-data-snapshot.json")
    fresh = _load_holdout(repo / "semantic/posttrain_v3_holdout.py", "sealed_v3_evaluation_cases")
    old_v2 = _load_holdout(repo / "semantic/posttrain_v2_holdout.py", "reused_v2_evaluation_cases")
    old_v1 = _load_holdout(repo / "semantic/posttrain_holdout_cases.py", "reused_v1_evaluation_cases")
    require(len(fresh) == 40 and sum(not row["pos"] for row in fresh) == 8, "fresh_holdout_counts_changed")
    seen = data["train"] + data["dev"] + ancestor["train"] + ancestor["dev"]
    require(not passage_set(seen) & passage_set(fresh), "fresh_holdout_passage_overlap")
    require(not {row["group"] for row in fresh} & {row["group"] for row in seen}, "fresh_holdout_group_overlap")
    splits = (("dev", data["dev"]), ("fresh_holdout", fresh),
              ("reused_v2_diagnostic", old_v2), ("reused_diagnostic", old_v1))
    test_rows, cases = [], []
    for split, rows in splits:
        for original in rows:
            row = dict(original, family=original.get("family", original["id"]), split=split)
            docs = [(text, True) for text in row["pos"]] + [(text, False) for text in row["neg"]]
            require(bool(docs) and len({text for text, _ in docs}) == len(docs), "empty_or_duplicate_candidates")
            random.Random(int(hashlib.sha256(row["family"].encode()).hexdigest()[:12], 16)).shuffle(docs)
            row["documents"] = docs
            test_rows.append(row)
            cases.append(dict(id=row["id"], query=row["query"],
                              documents=[dict(id=index + 1, text=text) for index, (text, _) in enumerate(docs)]))
    require(len({case["id"] for case in cases}) == len(cases), "duplicate_case_ids")
    prepared = replay(cases, args.java, args.classpath, **identity, phase="prepare")
    validate_replay(cases, prepared, "prepare", identity)
    results, replay_cases = [], []
    for row, case, prep in zip(test_rows, cases, prepared):
        texts = [text for text, _ in row["documents"]]
        positive = {index + 1 for index, (_, label) in enumerate(row["documents"]) if label}
        encoder.cache.execute("DELETE FROM vectors"); encoder.cache.commit()
        started = time.monotonic()
        query_vector = encoder.encode(row["query"], query=True)
        scores = [float(encoder.np.dot(query_vector, encoder.encode(text))) for text in texts]
        require(all(math.isfinite(score) and -1.00001 <= score <= 1.00001 for score in scores), "raw_score_invalid")
        ranking = sorted(range(1, len(texts) + 1), key=lambda index: (-scores[index - 1], index))
        entry = dict(id=row["id"], family=row["family"], group=row["group"], split=row["split"],
                     positive_count=len(positive), positive_ids=sorted(positive), raw_scores=scores,
                     raw_top1=ranking[0], raw_top1_correct=ranking[0] in positive,
                     raw_mrr=1 / next(index + 1 for index, item in enumerate(ranking) if item in positive) if positive else None,
                     longest_heuristic_correct=max(range(1, len(texts) + 1), key=lambda index: len(texts[index - 1])) in positive,
                     raw_ms=round((time.monotonic() - started) * 1000, 3), worker_error=None)
        payload = dict(query=prep["focused_query"], documents=prep["prepared_documents"])
        encoder.cache.execute("DELETE FROM vectors"); encoder.cache.commit()
        try:
            started = time.monotonic(); response = encoder.compare(payload)
            cold = (time.monotonic() - started) * 1000
            started = time.monotonic(); warm = encoder.compare(payload)
            warm_ms = (time.monotonic() - started) * 1000
        except (ValueError, TimeoutError) as error:
            entry.update(worker_error=f"{type(error).__name__}: {error}", worker_top1=None,
                         worker_top1_correct=False, worker_positive_present=False, worker_abstained=False)
            replay_cases.append(dict(case, semantic_error="evaluation_worker_failed"))
        else:
            validate_worker_response(response, set(range(1, len(texts) + 1)), identity)
            require({key: value for key, value in warm.items() if key != "elapsed_ms"} ==
                    {key: value for key, value in response.items() if key != "elapsed_ms"}, "warm_response_changed")
            valid = sorted((item for item in response["results"] if item["similarity"] is not None),
                           key=lambda item: (-item["similarity"], item["id"]))
            entry.update(worker_top1=valid[0]["id"] if valid else None,
                         worker_top1_correct=bool(valid) and valid[0]["id"] in positive,
                         worker_positive_present=any(item["id"] in positive for item in valid), worker_abstained=not valid,
                         worker_cold_ms=round(cold, 3), worker_warm_ms=round(warm_ms, 3))
            replay_cases.append(dict(case, semantic_response=response))
        results.append(entry)
    pipeline = replay(replay_cases, args.java, args.classpath, **identity, phase="evaluate")
    validate_replay(replay_cases, pipeline, "evaluate", identity)
    for row, outcome in zip(results, pipeline):
        positive, admitted = set(row["positive_ids"]), outcome["admitted_ids"]
        selected = [candidate["teacher_id"] for candidate in outcome["selected"]]
        row.update(pipeline_admitted_ids=admitted, pipeline_wrongly_admitted_ids=[item for item in admitted if item not in positive],
                   pipeline_positive_admitted=bool(positive & set(admitted)), pipeline_no_admission=not admitted,
                   pipeline_has_review=bool(outcome["review"]), pipeline_top1_correct=bool(selected) and selected[0] in positive,
                   pipeline_selected_ids=selected, pipeline_semantic_state=outcome["semantic_state"])
    require(sha(args.v3_training_dir / "freeze.json") == frozen_bytes and
            sha(args.v3_training_dir / "training-report.json") == training_report_bytes,
            "training_metadata_changed_during_evaluation")
    verify_frozen_training(args.v3_training_dir, repo)
    require(capture_classpath(args.classpath) == classes, "classpath_changed_during_evaluation")
    require(read_json(args.model_dir / "manifest.json") == manifest, "manifest_changed_during_evaluation")
    verify_artifact(args.model_dir, args.precision, artifact_dir, trained, frozen)
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024 * 1024 if sys.platform == "darwin" else 1024)
    report = dict(generated_at=datetime.now(timezone.utc).isoformat(), experiment=EXPERIMENT, **identity,
                  model_sha256=encoder.version, model_bytes=(args.model_dir / f"model-{args.precision}.onnx").stat().st_size,
                  inference_process_peak_rss_mib=round(rss, 3), startup_seconds=round(startup, 3),
                  final_checkpoint_before_holdout=True, production_deployed=False, real_business_accuracy=False,
                  author_naive_blind_test=False, v3_checkpoint_sha256=trained["checkpoint_sha256"],
                  frozen_manifest_sha256=frozen_bytes, training_report_sha256=training_report_bytes,
                  fresh_holdout_sha256=frozen["holdout_generator_sha256"],
                  worker_sha256=sha(repo / "semantic/worker.py"), evaluator_sha256=sha(Path(__file__)),
                  probe_sha256=sha(repo / "scripts/PosttrainingV2Probe.java"),
                  executed_classpath_sha256=classes,
                  summary={split: summarize([row for row in results if row["split"] == split]) for split, _ in splits},
                  results=results,
                  metric_note="V3 author-created synthetic family-heldout retrieval and offline replay; not real-world blind accuracy or production HTTP validation. No-match is excluded from positive Top1/MRR. Review and errors are not pure rejection. Reused sets are diagnostic; no checkpoint selection.")
    output.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    with output.open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps({key: value for key, value in report.items() if key not in {"results", "executed_classpath_sha256"}},
                     ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
