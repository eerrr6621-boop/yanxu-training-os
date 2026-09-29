"""Reused synthetic regression diagnostics for evidence preprocessing only.

This is NOT a new blind test, model-selection routine, or production acceptance.
Uses only the approved base model, never resumes, experimental weights or HTTP.
Historical files are read-only; the output must be a new private JSON file.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import random
import time

from posttrain_holdout_cases import build_holdout
from worker import Encoder


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--old-cases", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    if output.is_relative_to(repo) or "web" in output.parts or output.exists():
        raise ValueError("Use a new private output path outside the repository")
    encoder = Encoder(args.model_dir, precision="int8")
    fixtures = []
    for row in build_holdout():
        documents = [(text, True) for text in row["pos"]] + [(text, False) for text in row["neg"]]
        random.Random(int(hashlib.sha256(row["id"].encode()).hexdigest()[:12], 16)).shuffle(documents)
        fixtures.append({"id": row["id"], "suite": "reused_pilot_32", "query": row["query"],
                         "documents": [{"id": i + 1, "text": text} for i, (text, _) in enumerate(documents)],
                         "relevant": [i + 1 for i, (_, positive) in enumerate(documents) if positive]})
    old = json.loads(args.old_cases.read_text(encoding="utf-8"))
    assert len(old["cases"]) == 72
    fixtures.extend(dict(row, suite="reused_twenty_rounds_72") for row in old["cases"])
    results = []
    for row in fixtures:
        encoder.cache.execute("DELETE FROM vectors")
        encoder.cache.commit()
        entry = {"id": row["id"], "suite": row["suite"], "has_positive": bool(row["relevant"]),
                 "retrieval_top1_correct": False, "positive_scored": False, "error": None}
        try:
            start = time.monotonic()
            response = encoder.compare({"query": row["query"], "documents": row["documents"]})
            ranking = sorted((item for item in response["results"] if item["similarity"] is not None),
                             key=lambda item: (-item["similarity"], item["id"]))
            entry.update(retrieval_top1_correct=bool(ranking) and ranking[0]["id"] in row["relevant"],
                         positive_scored=any(item["id"] in row["relevant"] for item in ranking),
                         elapsed_ms=round((time.monotonic() - start) * 1000, 3),
                         requires_requirement_review=response["requirement_analysis"]["requires_review"],
                         ranking=[{"id": item["id"], "similarity": item["similarity"]} for item in ranking],
                         review_documents=[{"id": item["id"], "positive": item["id"] in row["relevant"],
                                            "reasons": item["evidence_review_reasons"]}
                                           for item in response["results"] if not item["evidence_complete"]])
            for item in response["results"]:
                source = next(doc["text"] for doc in row["documents"] if doc["id"] == item["id"])
                for unit in item["evidence_units"]:
                    assert source[unit["source_start"]:unit["source_end"]] == unit["text"]
        except (ValueError, TimeoutError) as error:
            entry["error"] = str(error)
        results.append(entry)
    summary = {}
    for suite in ("reused_pilot_32", "reused_twenty_rounds_72"):
        rows = [row for row in results if row["suite"] == suite]
        reasons = Counter(reason for row in rows for doc in row.get("review_documents", []) for reason in doc["reasons"])
        summary[suite] = {"cases": len(rows), "positive_cases": sum(row["has_positive"] for row in rows),
                          "retrieval_top1_correct": sum(row["retrieval_top1_correct"] for row in rows),
                          "positive_scored": sum(row["positive_scored"] for row in rows),
                          "errors": sum(row["error"] is not None for row in rows),
                          "positive_withheld_case_ids": [row["id"] for row in rows if row["has_positive"] and not row["positive_scored"]],
                          "review_reason_counts": dict(reasons)}
    report = {"generated_at": datetime.now(timezone.utc).isoformat(), "production_deployed": False,
              "real_business_accuracy": False, "experimental_model_loaded": False,
              "note": "Historical synthetic diagnostic sets reused after fixes; retrieval only, not new blind validation or Java eligibility.",
              "worker_sha256": sha(repo / "semantic/worker.py"),
              "old_cases_sha256": sha(args.old_cases),
              "pilot_cases_module_sha256": sha(repo / "semantic/posttrain_holdout_cases.py"),
              "model_sha256": encoder.version, "summary": summary, "results": results}
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps({"output": str(output), "summary": summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
