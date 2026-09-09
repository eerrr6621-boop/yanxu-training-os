"""Diagnostic evaluation: expectations are frozen before inference; never tune the
production matcher in this script. Reports failures without hiding hard cases.
Uses constructed text and the actual Java rule gate/sorter, no databases/network.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import resource
import subprocess
import sys
import time
from worker import Encoder, chunks

parser = argparse.ArgumentParser()
parser.add_argument("--model-dir", type=Path, required=True)
parser.add_argument("--cases", type=Path, required=True)
parser.add_argument("--precision", choices=["int8", "fp32"], required=True)
parser.add_argument("--java", required=True)
parser.add_argument("--classpath", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
suite = json.loads(args.cases.read_text(encoding="utf-8"))

def probe(cases):
    process = subprocess.run([args.java, "-cp", args.classpath, "com.training.SemanticChallengeProbe"],
        input=json.dumps({"cases": cases}, ensure_ascii=False), text=True, capture_output=True, check=True, timeout=30)
    return json.loads(process.stdout)["cases"]

cases = copy.deepcopy(suite["cases"])
for case in cases:
    for doc in case["documents"]:
        if "prefix_repeat" in doc:
            doc["text"] = "\n".join(f"第{i+1}段：{doc['prefix_repeat']}" for i in range(doc["repeat_count"])) + "\n" + doc["text"]
baseline = {case["id"]: case for case in probe(cases)}
encoder = Encoder(args.model_dir, precision=args.precision)
results = []
model_cases = []
for case in cases:
    query = baseline[case["id"]]["focused_query"]
    started = time.monotonic()
    model = encoder.compare({"query": query, "documents": [{"id": doc["id"], "text": doc.get("manual_text",doc["text"])} for doc in case["documents"]]})
    elapsed = round((time.monotonic()-started)*1000, 2)
    valid = [row for row in model["results"] if row["similarity"] is not None]
    ranked = sorted(valid, key=lambda row: (-row["similarity"], row["id"]))
    shuffled = encoder.compare({"query": query, "documents": [{"id": doc["id"], "text": doc.get("manual_text",doc["text"])} for doc in reversed(case["documents"])]})
    assert {row["id"]:(row["similarity"],row["evidence"]) for row in shuffled["results"]} == {row["id"]:(row["similarity"],row["evidence"]) for row in model["results"]}, "Order-dependent model results"
    model_cases.append(dict(case, semantic_response=model))
    top = ranked[0]["id"] if ranked else None
    results.append({"id": case["id"], "category": case["category"], "query": case["query"],
        "expected_best_ids": case["expected_best_ids"], "rationale": case["rationale"],
        "semantic_top1": top, "semantic_pass": top in case["expected_best_ids"],
        "semantic_results": model["results"], "elapsed_ms": elapsed,
        "input_reversal_stable": True})

hybrid = {case["id"]: case for case in probe(model_cases)}
for result in results:
    actual = hybrid[result["id"]]
    result["rule_baseline_rank"] = actual["baseline_rank"]
    result["hybrid_rank"] = actual["hybrid_rank"]
    result["excluded_by_rules"] = actual["excluded_by_rules"]
    result["semantic_state"] = actual["semantic_state"]
    expected = result["expected_best_ids"]
    result["baseline_pass"] = bool(actual["baseline_rank"]) and actual["baseline_rank"][0] in expected
    result["hybrid_pass"] = bool(actual["hybrid_rank"]) and actual["hybrid_rank"][0]["id"] in expected
    result["expected_excluded_by_rules"] = all(ident in [row["id"] for row in actual["excluded_by_rules"]] for ident in expected)

preprocessing = []
for case in suite.get("preprocessing", []):
    actual = chunks(case["text"])
    kept = any(case.get("fragment",case["text"]) in piece for piece in actual)
    preprocessing.append(dict(case, actual_chunks=actual, passed=kept == case["should_keep"]))

scale = []
for size in (50, 200):
    encoder.cache.execute("DELETE FROM vectors")  # This encoder uses an in-memory synthetic test cache only.
    encoder.cache.commit()
    documents = [{"id":i+1,"text":f"第{i+1}位构造讲师：主讲银行客户服务与投诉处理课程。以第{i+1}个网点案例讲授情绪安抚与问题解决方法。"} for i in range(size)]
    started = time.monotonic()
    response = encoder.compare({"query":"银行客户投诉处理", "documents":documents})
    cold = (time.monotonic()-started)*1000
    started = time.monotonic()
    warm = encoder.compare({"query":"银行客户投诉处理", "documents":documents})
    assert warm["results"] == response["results"]
    scale.append({"candidates":size,"chunks":sum(len(chunks(doc["text"])) for doc in documents),
        "cold_ms":round(cold,2),"warm_ms":round((time.monotonic()-started)*1000,2),
        "complete":response["complete"]})

capacity = []
for name, documents in (
    ("201_candidates",[{"id":i+1,"text":"主讲银行客户服务与投诉处理课程"} for i in range(201)]),
    ("200_candidates_600_chunks",[{"id":i+1,"text":"主讲银行客户服务与投诉处理课程。以网点案例讲解客户情绪安抚方法。组织学员练习争议化解与服务补救。"} for i in range(200)])):
    try:
        encoder.compare({"query":"客户投诉处理", "documents":documents})
        capacity.append({"id":name,"rejected_as_expected":False})
    except ValueError as error:
        capacity.append({"id":name,"rejected_as_expected":str(error)=="capacity_limit","error":str(error)})

report = {"notice":"Constructed adversarial diagnostic cases. Expectations fixed before inference. Not real-business accuracy; no tuning or production changes.",
    "generated_at":datetime.now(timezone.utc).isoformat(),"platform":platform.system()+" "+platform.machine(),
    "precision":args.precision,"cases_sha256":hashlib.sha256(args.cases.read_bytes()).hexdigest(),
    "model_sha256":encoder.version,
    "source_sha256":{name:hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in (
        "semantic/worker.py","semantic/evaluate_challenges.py","src/com/training/LocalSemantic.java","src/com/training/TeacherIntelligence.java","scripts/SemanticChallengeProbe.java")},
    "total":len(results),"baseline_top1_correct":sum(row["baseline_pass"] for row in results),
    "semantic_only_top1_correct":sum(row["semantic_pass"] for row in results),
    "hybrid_top1_correct":sum(row["hybrid_pass"] for row in results),
    "hybrid_improvements":[row["id"] for row in results if row["hybrid_pass"] and not row["baseline_pass"]],
    "hybrid_regressions":[row["id"] for row in results if row["baseline_pass"] and not row["hybrid_pass"]],
    "expected_excluded_by_rules":[row["id"] for row in results if row["expected_excluded_by_rules"]],
    "preprocessing_passed":sum(row["passed"] for row in preprocessing),"preprocessing_total":len(preprocessing),
    "peak_rss_mib":round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/(1024*1024 if sys.platform=="darwin" else 1024),2),
    "scale":scale,"capacity":capacity,"results":results,"preprocessing":preprocessing}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps({key:value for key,value in report.items() if key not in ("results","preprocessing","source_sha256")},ensure_ascii=False,indent=2))
