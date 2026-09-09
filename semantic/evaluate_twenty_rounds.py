"""Run nine frozen synthetic semantic rounds, independently of ten traffic rounds
and the tenth historical real-resume API round. Never train/tune on this suite.
Generated raw reports stay in a NEW private directory outside the repository.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time
from twenty_round_cases import build_suite
from worker import Encoder


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    out = args.output_dir.resolve()
    repo = Path(__file__).resolve().parent.parent
    if out.is_relative_to(repo):
        raise SystemExit("Private evaluation output must be outside the repository")
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    suite = build_suite()
    write(out / "frozen-synthetic-cases.json", suite)
    sources = list((repo / "src/com/training").glob("*.java")) + [
        Path(__file__).resolve(), repo/"semantic/twenty_round_cases.py", repo/"semantic/worker.py",
        repo/"scripts/TwentyRoundSemanticProbe.java"]
    before = {str(p.relative_to(repo)): digest(p) for p in sources}
    freeze = dict(frozen_at=datetime.now(timezone.utc).isoformat(), source_sha256=before,
                  cases_sha256=digest(out/"frozen-synthetic-cases.json"),
                  labels="Author-labelled constructed cases, NOT business-confirmed labels",
                  model_manifest=json.loads((args.model_dir/"manifest.json").read_text()))
    write(out/"freeze.json", freeze)

    def probe(cases):
        process = subprocess.run([args.java, "-Ddispatch.transport.file=", "-Ddispatch.timetable.file=",
            "-cp", args.classpath, "com.training.TwentyRoundSemanticProbe"],
            input=json.dumps({"cases":cases}, ensure_ascii=False), capture_output=True,
            text=True, check=True, timeout=120)
        return {r["id"]:r for r in json.loads(process.stdout)["cases"]}

    baseline = probe(suite["cases"])
    write(out/"baseline-private.json", baseline)
    all_summaries = {}
    for precision in ("int8", "fp32"):
        encoder = Encoder(args.model_dir, precision=precision)
        variants, measurements = [], {}
        for case in suite["cases"]:
            source = baseline[case["id"]]
            payload = dict(query=source["focused_query"], documents=source["prepared_documents"])
            start = time.monotonic()
            measured = dict(elapsed_ms=None, error=None, model_top1=None)
            item = copy.deepcopy(case)
            try:
                response = encoder.compare(payload)
                measured["elapsed_ms"] = round((time.monotonic()-start)*1000,2)
                ranked = sorted((r for r in response["results"] if r["similarity"] is not None),
                                key=lambda r:(-r["similarity"],r["id"]))
                measured["model_top1"] = ranked[0]["id"] if ranked else None
                measured["model_results"] = response["results"]
                item["semantic_response"] = response
                reverse = encoder.compare(dict(query=payload["query"], documents=list(reversed(payload["documents"]))))
                measured["model_reversal_stable"] = sorted(reverse["results"],key=lambda r:r["id"]) == sorted(response["results"],key=lambda r:r["id"])
            except (ValueError, TimeoutError) as exc:
                measured["error"] = str(exc)
                item["semantic_error"] = str(exc)
            variants.append(item)
            measurements[case["id"]] = measured
        actual = probe(variants)
        reversed_cases = copy.deepcopy(variants)
        for case in reversed_cases:
            case["documents"].reverse()
        reversed_actual = probe(reversed_cases)
        transformed = []
        for case in suite["cases"]:
            if not case.get("transform"):
                continue
            item = copy.deepcopy(case)
            item["query"] = "客户单位：【灰度测试】模拟客户\n" + item["query"] + "\n培训时间：2030-01-15\n项目总预算：100000"
            for i, doc in enumerate(item["documents"]):
                doc["text"] = ("性别：女。年龄：52。\n" if i%2 else "性别：男。年龄：28。\n") + doc["text"]
            transformed.append(item)
        transformed_prepared = probe(transformed)
        for item in transformed:
            ready = transformed_prepared[item["id"]]
            try:
                item["semantic_response"] = encoder.compare(dict(query=ready["focused_query"], documents=ready["prepared_documents"]))
            except (ValueError,TimeoutError) as exc:
                item["semantic_error"] = str(exc)
        transformed_actual = probe(transformed)
        details = []
        for case in suite["cases"]:
            ident=case["id"]
            row=actual[ident]
            expected=set(case["relevant"])
            selected=[r["teacher_id"] for r in row["selected"]]
            admitted=set(row["admitted_ids"])
            raw=measurements[ident]
            rank_signature=lambda r:[(c["teacher_id"],c["model_score"]) for c in r["selected"]]
            details.append(dict(id=ident, round=case["round"], category=case["category"],
                expected=sorted(expected), selected=selected, admitted=sorted(admitted),
                baseline_selected=[r["teacher_id"] for r in baseline[ident]["selected"]],
                review_ids=[r["teacher_id"] for r in row["review"]],
                excluded_ids=row["excluded_ids"], false_selected=sorted(set(selected)-expected),
                missing_from_admitted=sorted(expected-admitted),
                positive_top1_correct=bool(selected) and selected[0] in expected if expected else None,
                all_selected_supported=set(selected)<=expected,
                correct_rejection=not selected if not expected else None,
                selected_true=len(set(selected)&expected),
                case_pass=(bool(selected) and selected[0] in expected and set(selected)<=expected) if expected else not selected,
                semantic_state=row["semantic_state"], model=raw,
                selection_reversal_stable=rank_signature(row)==rank_signature(reversed_actual[ident]),
                attributes_and_metadata_stable=rank_signature(row)==rank_signature(transformed_actual[ident]) if ident in transformed_actual else None))
        write(out/f"{precision}-details-private.json", dict(actual=actual, details=details))

        def summarize(rows):
            positive=[r for r in rows if r["expected"]]
            negative=[r for r in rows if not r["expected"]]
            returned=sum(len(r["selected"]) for r in rows)
            correct=sum(r["selected_true"] for r in rows)
            expected=sum(len(r["expected"]) for r in rows)
            admission=sum(len(set(r["expected"])&set(r["admitted"])) for r in rows)
            times=[r["model"]["elapsed_ms"] for r in rows if r["model"]["elapsed_ms"] is not None]
            return dict(cases=len(rows), case_pass=sum(r["case_pass"] for r in rows),
                positive_top1_correct=sum(r["positive_top1_correct"] is True for r in positive), positive_cases=len(positive),
                selected_true=correct, selected_total=returned,
                selected_precision=correct/returned if returned else None,
                admitted_relevant=admission, expected_relevant=expected,
                admission_recall=admission/expected if expected else None,
                negative_correct=sum(r["correct_rejection"] is True for r in negative), negative_cases=len(negative),
                raw_model_top1_correct=sum(r["model"]["model_top1"] in r["expected"] for r in positive),
                model_ready=sum(r["semantic_state"]["status"]=="ready" for r in rows),
                errors=[dict(id=r["id"], error=r["model"]["error"]) for r in rows if r["model"]["error"]],
                failed_case_ids=[r["id"] for r in rows if not r["case_pass"]],
                median_model_ms=round(statistics.median(times),2) if times else None,
                max_model_ms=max(times) if times else None,
                selection_reversal_passed=sum(r["selection_reversal_stable"] for r in rows),
                model_reversal_passed=sum(r["model"].get("model_reversal_stable",False) for r in rows),
                attributes_metadata_passed=sum(r["attributes_and_metadata_stable"] is True for r in rows),
                attributes_metadata_total=sum(r["attributes_and_metadata_stable"] is not None for r in rows))
        rounds=[dict(round=r, name=next(c["category"] for c in suite["cases"] if c["round"]==r),
                     **summarize([row for row in details if row["round"]==r])) for r in range(1,10)]
        summary=dict(precision=precision, model_sha256=encoder.version, rounds=rounds, total=summarize(details),
                     cases_sha256=freeze["cases_sha256"], notice="Constructed diagnostic results, not production accuracy; source and labels frozen before inference")
        write(out/f"{precision}-summary.json",summary)
        all_summaries[precision]=summary
        print(json.dumps(summary,ensure_ascii=False),flush=True)
        encoder.cache.close()
        del encoder
    unchanged=before=={str(p.relative_to(repo)):digest(p) for p in sources}
    write(out/"completion.json",dict(completed=True, sources_unchanged=unchanged,
          finished_at=datetime.now(timezone.utc).isoformat(), synthetic_rounds=9,
          unique_cases=72, trained=False, deployed=False))
    if not unchanged:
        raise SystemExit("Source changed during evaluation; results are not a frozen baseline")


if __name__=="__main__":
    main()
