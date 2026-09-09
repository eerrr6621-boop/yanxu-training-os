"""Export anonymous counts only. Never publish resumes, profiles, source names,
credentials, source paths, private route fixtures or raw API responses.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--semantic-dir",type=Path,required=True)
    p.add_argument("--dispatch-report",type=Path,required=True)
    p.add_argument("--real-manifest",type=Path,required=True)
    p.add_argument("--executed-classes",type=Path,required=True)
    p.add_argument("--fresh-classes",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    if args.output.exists():
        raise SystemExit("Refuse to overwrite an audit result")
    completion=read(args.semantic_dir/"completion.json")
    assert completion["completed"] and completion["sources_unchanged"]
    freeze=read(args.semantic_dir/"freeze.json")
    repo=Path(__file__).resolve().parent.parent
    assert all(sha(repo/name)==value for name,value in freeze["source_sha256"].items())
    bytecode={}
    for fresh in sorted(args.fresh_classes.rglob("*.class")):
        relative=fresh.relative_to(args.fresh_classes)
        executed=args.executed_classes/relative
        assert executed.exists() and sha(fresh)==sha(executed), f"Bytecode mismatch: {relative}"
        bytecode[str(relative)]=sha(executed)
    precision={key:read(args.semantic_dir/f"{key}-summary.json") for key in ("int8","fp32")}
    details={key:read(args.semantic_dir/f"{key}-details-private.json")["details"] for key in precision}
    assert precision["int8"]["cases_sha256"]==precision["fp32"]["cases_sha256"]==freeze["cases_sha256"]
    changed=[]
    by_id={r["id"]:r for r in details["fp32"]}
    for row in details["int8"]:
        other=by_id[row["id"]]
        if row["selected"]!=other["selected"]:
            changed.append(dict(case_id=row["id"], set_changed=set(row["selected"])!=set(other["selected"])))

    manifest=read(args.real_manifest)
    assert manifest["completed"] and not manifest["production_touched"]
    assert len(manifest["teachers"])==19 and len(manifest["results"])==8
    real=[]
    for entry in manifest["results"]:
        expected=set(entry["case"]["relevant"])
        selected=entry["top3"]
        real.append(dict(case_id=entry["case"]["id"], positive=bool(expected),
            expected_relevant=len(expected), returned=len(selected), correct=len(set(selected)&expected),
            false_selected=len(set(selected)-expected),
            top1_correct=bool(selected) and selected[0] in expected if expected else None,
            correct_rejection=not selected if not expected else None,
            model_status=entry["result"]["analysis"]["semantic_matching"]["status"],
            elapsed_ms=entry["elapsed_ms"], algorithm_version=entry["result"]["algorithm_version"]))
    returned=sum(r["returned"] for r in real)
    correct=sum(r["correct"] for r in real)
    real_summary=dict(round=10,name="19份真实简历重新入库/上传后8条历史需求回归",cases=8,
        unique_resumes=19, positive_cases=sum(r["positive"] for r in real),
        positive_top1_correct=sum(r["top1_correct"] is True for r in real),
        selected_true=correct,selected_total=returned,selected_precision=correct/returned if returned else None,
        false_selected=returned-correct, negative_cases=sum(not r["positive"] for r in real),
        negative_correct=sum(r["correct_rejection"] is True for r in real),
        model_ready=sum(r["model_status"]=="ready" for r in real),
        known_previous_cases=True,business_confirmed_labels=False,
        label_note="沿用此前冻结的严格相关集合；备选不算正式正确。旧需求与旧简历已被用于诊断，不是新讲师盲测。",
        mutation_note="19名讲师和8条需求仅在全新隔离库建立，均标【灰度测试】；未自动新增投标、项目、排课和课酬。",
        raw_manifest_sha256=sha(args.real_manifest), results=real)

    dispatch=read(args.dispatch_report)
    assert dispatch["round_count"]==10 and [r["round"] for r in dispatch["rounds"]]==list(range(11,21))
    transport={k:dispatch[k] for k in ("schema_version","scope_note","assertion_metric_note","assertion_count","failed_count","passed_count","rounds","status")}
    for row in transport["rounds"]:
        row.pop("failure_examples",None)
    report=dict(schema_version="twenty-round-evaluation-v1", generated_at_utc=datetime.now(timezone.utc).isoformat(),
        unique_rounds=20,semantic_unique_cases=80, production_accuracy_claim=False, trained=False,deployed=False,
        limits=["构造题由评测者预先设定并经独立复核，不是业务人员确认的真实准确率。",
                "相关主题变体不是独立用户样本，断言数不能当样本量或路线准确率。",
                "第10轮复用历史19份简历和8条需求，只是回归；未训练，未调参。",
                "交通全部采用明确标注的合成时刻与覆盖声明，只测程序规则，不验证全国真实时刻。"],
        synthetic_semantic=precision, real_resume_round=real_summary, transport=transport,
        quantization_comparison=dict(changed_rankings=changed, selected_set_change_count=sum(r["set_changed"] for r in changed)),
        sources_sha256=freeze["source_sha256"], executed_production_classes_sha256=bytecode,
        production_class_count=len(bytecode), fresh_compile_equals_executed=True,
        frozen_at=freeze["frozen_at"], cases_sha256=freeze["cases_sha256"])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(rounds=20,real=real_summary,transport_assertions=dispatch["assertion_count"],
          transport_failures=dispatch["failed_count"],quantization=report["quantization_comparison"],
          production_class_count=len(bytecode)),ensure_ascii=False,indent=2))


if __name__=="__main__":
    main()
