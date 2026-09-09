"""V4 paired offline evaluation; fixed final weights precede sealed-label access.

Model failures remain failures. The actual rule-only fallback is reported as a
separate diagnostic, never counted as a successful model prediction. No DB/HTTP.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import random
import resource
import sys
import time

from posttrain_v4_artifacts import (require, sha, read_json, capture_classpath,
    verify_frozen_training, verify_tokenizer_semantics, V4_MODEL)
from evaluate_posttrain_v3 import validate_replay, validate_worker_response, _load_holdout
from evaluate_posttrain_v2 import replay
from evaluate_posttrain_pilot import EvaluationEncoder
from posttrain_runtime_manifest import capture_runtime


def artifact(model_dir, precision, training_dir, frozen, current_training):
    manifest = read_json(model_dir / "manifest.json")
    filename = "model-" + precision + ".onnx"
    require(manifest.get("dimensions") == 512 and manifest.get("pooling") == "CLS+L2" and
            manifest.get("max_tokens") == 512, "model_contract")
    for name in (filename, "tokenizer.json"):
        require(sha(model_dir / name) == manifest["sha256"][name], "artifact_changed:" + name)
    base = frozen["base_manifest"]
    require(manifest["sha256"]["tokenizer.json"] == base["sha256"]["tokenizer.json"], "base_tokenizer")
    if manifest.get("schema_version") != "yanxu-experimental-embedding-v1":
        require(manifest["model"] == base["model"] and manifest["revision"] == base["revision"] and
                manifest["sha256"][filename] == base["sha256"][filename], "not_frozen_base")
        return manifest, current_training
    require(training_dir is not None, "experimental_lineage_required")
    trained = read_json(training_dir / "training-report.json")
    require(trained.get("completed") is True and trained.get("trained") is True and
            trained.get("deployed") is False and manifest.get("production_approved") is False,
            "experiment_not_complete_or_already_deployed")
    weight = sha(training_dir / "checkpoint/model.safetensors")
    require(weight == trained["checkpoint_sha256"] == manifest["trained_weights_sha256"], "weights_not_bound")
    prefix = {"yanxu/bge-small-zh-synthetic-pilot": "pilot-",
              "yanxu/bge-small-zh-anchored-v3": "v3-", V4_MODEL: "v4-"}
    require(manifest.get("model") in prefix and
            manifest["revision"] == prefix[manifest["model"]] + weight[:16], "artifact_identity")
    require(manifest.get("base_model") == base["model"] and manifest.get("base_revision") == base["revision"] and
            manifest["training_config"] == trained["config"], "artifact_configuration")
    if "checkpoint_config_sha256" in trained:
        require(sha(training_dir / "checkpoint/config.json") == trained["checkpoint_config_sha256"], "checkpoint_config")
    verify_tokenizer_semantics(training_dir / "checkpoint", model_dir / "tokenizer.json")
    if manifest["model"] == V4_MODEL:
        require(weight == current_training["checkpoint_sha256"] and
                manifest["freeze_sha256"] == sha(training_dir / "freeze.json") and
                manifest["training_report_sha256"] == sha(training_dir / "training-report.json"), "v4_metadata")
    return manifest, trained


def validate_cases(cases):
    require(isinstance(cases, list) and len(cases) == 20, "sealed_case_count")
    require(len({r["id"] for r in cases}) == len(cases), "duplicate_case_id")
    for row in cases:
        documents = row["documents"]
        require(isinstance(row["query"], str) and row["query"].strip() and len(documents) == 4, "case_shape")
        require({d["id"] for d in documents} == {1, 2, 3, 4} and
                len({d["text"] for d in documents}) == 4, "candidate_inventory")
        require(len(row["positive_ids"]) in (0, 1) and
                set(row["positive_ids"]) <= {1, 2, 3, 4}, "positive_ids")
    require(sum(bool(r["positive_ids"]) for r in cases) == 16, "sealed_positive_count")


def reused_cases(repo):
    result = []
    for split, filename, count in (("reused_v3", "posttrain_v3_holdout.py", 40),
                                    ("reused_v2", "posttrain_v2_holdout.py", 40),
                                    ("reused_v1", "posttrain_holdout_cases.py", 32)):
        rows = _load_holdout(repo / "semantic" / filename, "v4_diagnostic_" + split)
        require(len(rows) == count, "reused_inventory")
        for row in rows:
            documents = [(s, True) for s in row["pos"]] + [(s, False) for s in row["neg"]]
            family = row.get("family", row["id"])
            random.Random(int(hashlib.sha256(family.encode()).hexdigest()[:12], 16)).shuffle(documents)
            result.append(dict(id=row["id"], family=family, split=split, query=row["query"],
                documents=[dict(id=i+1, text=text) for i, (text, _) in enumerate(documents)],
                positive_ids=[i+1 for i, (_, positive) in enumerate(documents) if positive]))
    return result


def attach_outcome(entry, outcome, fallback):
    positives = set(entry["positive_ids"])
    admitted = outcome["admitted_ids"]
    selected = [c["teacher_id"] for c in outcome["selected"]]
    failed = entry["worker_error"] is not None
    entry.update(pipeline_admitted_ids=admitted,
        pipeline_positive_admitted=not failed and bool(positives & set(admitted)),
        pipeline_wrongly_admitted_ids=[n for n in admitted if n not in positives],
        pipeline_selected_ids=selected,
        pipeline_top1_correct=not failed and bool(selected) and selected[0] in positives,
        pipeline_has_review=bool(outcome["review"]), pipeline_semantic_state=outcome["semantic_state"],
        pipeline_review_checks=[dict(id=c["teacher_id"], checks=c.get("requirement_coverage", []))
                                for c in outcome["review"]])
    # prepare() uses the same candidate construction and coverage.assess(...,false)
    # as the actual LocalSemantic unavailable branch (no candidate semantic map).
    # Keep the legacy strict-error outcome above for comparable historical metrics.
    entry["rule_fallback"] = None if not failed else dict(
        admitted_ids=fallback["admitted_ids"],
        positive_admitted=bool(positives & set(fallback["admitted_ids"])),
        wrongly_admitted_ids=[n for n in fallback["admitted_ids"] if n not in positives],
        selected_ids=[c["teacher_id"] for c in fallback["selected"]],
        counted_as_model_success=False, diagnostic_only=True)


def summarize(rows):
    positive = [r for r in rows if r["positive_ids"]]
    no_match = [r for r in rows if not r["positive_ids"]]
    return dict(cases=len(rows), positive_cases=len(positive), no_match_cases=len(no_match),
        raw_top1_correct=sum(r["raw_top1_correct"] for r in positive),
        worker_top1_correct=sum(r["worker_top1_correct"] for r in positive),
        worker_errors=sum(r["worker_error"] is not None for r in rows),
        raw_errors=sum(r["raw_error"] is not None for r in rows),
        positive_admitted=sum(r["pipeline_positive_admitted"] for r in positive),
        wrong_admission_cases=sum(bool(r["pipeline_wrongly_admitted_ids"]) for r in rows),
        no_match_pending_review=sum(r["pipeline_has_review"] for r in no_match),
        rule_fallback_positive_cases=sum(bool(r["rule_fallback"] and r["rule_fallback"]["positive_admitted"]) for r in rows),
        rule_fallback_wrong_cases=sum(bool(r["rule_fallback"] and r["rule_fallback"]["wrongly_admitted_ids"]) for r in rows))


def main():
    if sys.flags.optimize:
        raise ValueError("Run without -O")
    parser = argparse.ArgumentParser()
    parser.add_argument("--v4-training-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--artifact-training-dir", type=Path)
    parser.add_argument("--precision", choices=("fp32", "int8"), required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); repo = Path(__file__).resolve().parent.parent
    require(not args.output.exists() and not args.output.resolve().is_relative_to(repo) and
            not any(p in {"web", "public", "static"} for p in args.output.resolve().parts), "new_private_output_required")
    trained, frozen = verify_frozen_training(args.v4_training_dir, repo)
    runtime = read_json(args.v4_training_dir / "runtime-snapshot.json")
    require(capture_runtime() == runtime, "runtime_dependencies_changed_before_evaluation")
    require(capture_classpath(args.classpath) == frozen["evaluation_classpath_sha256"], "executed_classes_changed")
    manifest, model_training = artifact(args.model_dir, args.precision, args.artifact_training_dir, frozen, trained)
    inventory_paths = [Path(args.java), Path(sys.executable), args.model_dir / "manifest.json",
                       args.model_dir / ("model-" + args.precision + ".onnx"), args.model_dir / "tokenizer.json"]
    if args.artifact_training_dir:
        inventory_paths += [args.artifact_training_dir / n for n in
                            ("training-report.json", "checkpoint/model.safetensors", "checkpoint/config.json")]
    before = {str(p): sha(p) for p in inventory_paths}
    # First label parsing occurs only after a complete, fixed checkpoint exists.
    sealed = read_json(Path(frozen["sealed_holdout_path"]))
    cases = sealed["cases"] if isinstance(sealed, dict) else sealed
    validate_cases(cases)
    cases = [dict(c, split="fresh_v4") for c in cases] + reused_cases(repo)
    training_data = read_json(args.v4_training_dir / "data-snapshot.json")
    strings = {s for r in training_data["train"] for s in [r["query"]] + r["pos"] + r["neg"] + r["factual_passages"]}
    require(not strings & {s for c in cases if c["split"] == "fresh_v4" for s in [c["query"]] + [d["text"] for d in c["documents"]]}, "heldout_exact_training_overlap")
    started = time.monotonic(); encoder = EvaluationEncoder(args.model_dir, args.precision, model_training)
    startup = time.monotonic() - started
    identity = dict(model=encoder.actual_model, revision=encoder.actual_revision, precision=args.precision)
    prepared = replay(cases, args.java, args.classpath, **identity, phase="prepare")
    validate_replay(cases, prepared, "prepare", identity)
    results, pending = [], []
    for case, prep in zip(cases, prepared):
        positive = set(case["positive_ids"])
        entry = dict(id=case["id"], split=case["split"], positive_ids=case["positive_ids"],
                     raw_error=None, worker_error=None)
        encoder.cache.execute("DELETE FROM vectors"); encoder.cache.commit()
        try:
            q = encoder.encode(case["query"], query=True)
            scores = {d["id"]: float(encoder.np.dot(q, encoder.encode(d["text"]))) for d in case["documents"]}
            require(all(math.isfinite(v) and -1.00001 <= v <= 1.00001 for v in scores.values()), "raw_score_invalid")
            ranking = sorted(scores, key=lambda ident: (-scores[ident], ident))
            entry.update(raw_scores=scores, raw_top1=ranking[0], raw_top1_correct=ranking[0] in positive)
        except (ValueError, TimeoutError) as error:
            entry.update(raw_error=type(error).__name__ + ": " + str(error), raw_top1=None, raw_top1_correct=False, raw_scores={})
        encoder.cache.execute("DELETE FROM vectors"); encoder.cache.commit()
        try:
            response = encoder.compare(dict(query=prep["focused_query"], documents=prep["prepared_documents"]))
        except (ValueError, TimeoutError) as error:
            entry.update(worker_error=type(error).__name__ + ": " + str(error), worker_top1=None, worker_top1_correct=False, worker_positive_present=False)
            pending.append(dict(case, semantic_error="worker_failed"))
        else:
            validate_worker_response(response, {d["id"] for d in case["documents"]}, identity)
            ranking = sorted((r for r in response["results"] if r["similarity"] is not None), key=lambda r: (-r["similarity"], r["id"]))
            entry.update(worker_top1=ranking[0]["id"] if ranking else None,
                worker_top1_correct=bool(ranking) and ranking[0]["id"] in positive,
                worker_positive_present=any(r["id"] in positive for r in ranking),
                worker_candidates=response["results"])
            pending.append(dict(case, semantic_response=response))
        results.append(entry)
    outcomes = replay(pending, args.java, args.classpath, **identity, phase="evaluate")
    validate_replay(pending, outcomes, "evaluate", identity)
    for entry, outcome, fallback in zip(results, outcomes, prepared):
        attach_outcome(entry, outcome, fallback)
    verify_frozen_training(args.v4_training_dir, repo)
    require(capture_classpath(args.classpath) == frozen["evaluation_classpath_sha256"], "classpath_drift_after_run")
    require(before == {str(p): sha(p) for p in inventory_paths}, "artifact_or_runtime_drift")
    artifact(args.model_dir, args.precision, args.artifact_training_dir, frozen, trained)
    require(capture_runtime() == runtime, "runtime_dependencies_changed_during_evaluation")
    summary = {split: summarize([r for r in results if r["split"] == split])
               for split in ("fresh_v4", "reused_v3", "reused_v2", "reused_v1")}
    report = dict(experiment="v4-frozen-paired-evaluation", generated_at=datetime.now(timezone.utc).isoformat(),
        **identity, model_sha256=encoder.version, freeze_sha256=sha(args.v4_training_dir / "freeze.json"),
        sealed_holdout_sha256=frozen["sealed_holdout_sha256"], checked_artifact_and_executables=before,
        runtime_manifest_sha256=frozen["runtime_manifest_sha256"], runtime_fingerprints_unchanged=True,
        summary=summary, results=results, production_deployed=False, real_business_accuracy=False,
        startup_seconds=round(startup, 3),
        inference_peak_rss_mib=round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024*1024 if sys.platform == "darwin" else 1024), 3),
        note="Fresh author-created synthetic holdout is separate from reused diagnostics. Model errors are never successful predictions; rule fallback is separate. No checkpoint selection.")
    with args.output.open("x", encoding="utf-8") as stream:
        args.output.chmod(0o600); json.dump(report, stream, ensure_ascii=False, indent=2); stream.write("\n")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
