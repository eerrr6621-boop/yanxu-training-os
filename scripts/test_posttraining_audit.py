"""Synthetic protocol/observability regression. No model, network, or old cases.

Run with -B and either --standalone for the current contract only, or separate
new/old probe classpaths compiled against identical business classes. Standalone
never claims old/new compatibility. Fixture scores are explicitly fictional
protocol inputs, never a worker run or a capability/accuracy result.
"""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys

IDENTITY = dict(expected_model="offline/audit-protocol-fixture",
                expected_revision="synthetic-not-a-model", expected_precision="int8")
CLASS = "com.training.PosttrainingV2Probe"


def block(course="标本封口标记", actor="本人", audience="实验助理"):
    return (f"课程名称：{course}\n授课人：{actor}\n授课角色：独立主讲\n"
            f"完成状态：已完成\n培训对象：{audience}")


def cases():
    return [
        dict(id="audit-three-partition", query="培训主题：标本封口标记\n授课对象：实验助理\n补充要求：需有本人实际主讲经历。",
             documents=[dict(id=1, text=block()), dict(id=2, text=block(actor="另一位老师")),
                        dict(id=3, text="本人曾独立主讲棋盘摆放课程。")]),
        dict(id="audit-unknown-requirement", query="需要一位老师；唯一条件是曾给飞行员讲过星图贴标课。",
             documents=[dict(id=1, text=block("星图贴标", audience="飞行员")), dict(id=2, text="我整理过星图贴标讲义。")]),
        dict(id="audit-two-events", query="培训主题：标本封口标记、试管架索引\n补充要求：两门都需本人讲过。",
             documents=[dict(id=1, text=block()+"\n\n"+block("试管架索引")),
                        dict(id=2, text=block()+"\n\n"+block("试管架索引", actor="同事"))]),
        dict(id="audit-empty-source", query="培训主题：标本封口标记", documents=[dict(id=1, text="")]),
        dict(id="audit-manual-effective", query="培训主题：标本封口标记",
             documents=[dict(id=1, text="旧提取内容", manual_text=block()), dict(id=2, text="本人只做物品搬运。")]),
        dict(id="audit-unicode", query="培训主题：标本封口标记",
             documents=[dict(id=1, text="🧪本人已经主讲过标本封口标记。"), dict(id=2, text="计划开课，尚未完成。")]),
        dict(id="audit-postselection-copy", query="培训主题：标本封口标记",
             documents=[dict(id=i, text=block()) for i in range(1, 7)]),
    ]


def invoke(java, cp, payload=None, self_test=False):
    command=[java,"-cp",cp,CLASS]+(["--self-test"] if self_test else [])
    run=subprocess.run(command, input=None if self_test else json.dumps(payload, ensure_ascii=False),
                       text=True, capture_output=True, timeout=30)
    return run, json.loads(run.stdout) if run.returncode == 0 else None


def fixture_response(prepared):
    rows=[]
    for document in prepared["prepared_documents"]:
        text=document["text"]
        unit=dict(text=text, source_start=0, source_end=len(text), paragraph_start=0,
                  paragraph_end=len(text), offset_unit="unicode_code_point", kind="retrieval_only",
                  context_status="paragraph_preserved", section_heading=None)
        rows.append(dict(id=document["id"], similarity=(0.31+document["id"]*0.05) if text else None,
                         evidence=[text] if text else [], evidence_units=[unit] if text else [],
                         evidence_complete=True, evidence_review_reasons=[]))
    return dict(model=IDENTITY["expected_model"], revision=IDENTITY["expected_revision"],
                precision="int8", status="ready", complete=True, experimental_evaluation=True,
                results=rows, requirement_analysis=dict(positive_fragments=[prepared["focused_query"]],
                    exclusions=[], requires_review=False, exclusions_enforced=False, review_reasons=[]))


def strip_audit(output):
    value=copy.deepcopy(output)
    for case in value["cases"]:
        case.pop("audit", None)
    return value


def main():
    assert sys.dont_write_bytecode
    parser=argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--baseline-classpath")
    mode.add_argument("--standalone", action="store_true")
    parser.add_argument("--output", required=True)
    args=parser.parse_args()
    counter=0
    saved=[]

    def check(value, message):
        nonlocal counter
        assert value, message
        counter+=1

    classpaths=(args.classpath,) if args.standalone else (args.classpath,args.baseline_classpath)
    for cp in classpaths:
        run, result=invoke(args.java,cp,self_test=True)
        check(run.returncode == 0 and result["protocol_tests_passed"] == 18,"existing_self_tests")
    prepared_input=dict(**IDENTITY, phase="prepare", cases=cases())
    if not args.standalone:
        old_run, old_prepared=invoke(args.java,args.baseline_classpath,prepared_input)
    new_run, new_prepared=invoke(args.java,args.classpath,prepared_input)
    check(new_run.returncode == 0 and (args.standalone or old_run.returncode == 0),"prepare_success")
    if not args.standalone:
        check(strip_audit(new_prepared) == old_prepared,"prepare_old_fields_byte_value_compatible")
    excluded_seen=admitted_seen=False
    for phase in ("prepare","response","error","legacy_error"):
        payload=copy.deepcopy(prepared_input)
        if phase != "prepare":
            payload["phase"]="evaluate"
        for case, prepared in zip(payload["cases"],new_prepared["cases"]):
            if phase == "response":
                case["semantic_response"]=fixture_response(prepared)
            elif phase in ("error","legacy_error"):
                case["semantic_error"]="synthetic_worker_error"
                if phase == "error":
                    case["semantic_error_details"]=dict(type="SyntheticError",message="原始错误 🧪",
                                                        causes=[dict(type="Cause", message="完整原因")])
        if not args.standalone:
            old_run, old=invoke(args.java,args.baseline_classpath,payload)
        new_run, new=invoke(args.java,args.classpath,payload)
        check(new_run.returncode == 0 and (args.standalone or old_run.returncode == 0),phase+"_success")
        if not args.standalone:
            check(strip_audit(new) == old,phase+"_unchanged_business_output")
        for original, result in zip(payload["cases"],new["cases"]):
            audit=result["audit"]
            check(audit["schema_version"] == "posttraining_candidate_audit_v1","schema")
            check(audit["original_query"] == original["query"] and audit["input_documents"] == original["documents"],"original_input")
            check(audit["worker_request"] == dict(query=result["focused_query"],documents=result["prepared_documents"]),"actual_worker_request")
            check(len(audit["candidates"]) == len(original["documents"]),"all_candidates")
            byid={c["teacher_id"]:c for c in audit["candidates"]}
            check(list(byid) == [d["id"] for d in original["documents"]],"input_order")
            sem=audit["semantic_input"]
            check(sem["response"] == original.get("semantic_response"),"raw_response_preserved")
            check(sem["error"] == original.get("semantic_error") and sem["error_details"] == original.get("semantic_error_details"),"raw_error_preserved")
            check(not sem["model_invoked_by_probe"] and not sem["error_or_rule_prepare_is_model_success"] and not sem["production_http_fallback_observed"],"not_inference_or_production")
            for index, candidate in enumerate(audit["candidates"]):
                ident=candidate["teacher_id"]
                expected="admitted" if ident in result["admitted_ids"] else "excluded" if ident in result["excluded_ids"] else "review"
                check(candidate["disposition_before_selection"] == expected,"same_actual_disposition")
                check(candidate["input_document_index"] == index,"index")
                assessed=candidate["candidate_after_assessment"]
                check(all(k in assessed for k in ("_admitted","_review_candidate","_rule_relevant","teaching_events","requirement_coverage","requirement_topic_contract")),"pre_filter_full_snapshot")
                check(candidate["observed_gates"]["requirement_checks"] == assessed["requirement_coverage"],"same_assessment_checks")
                check(candidate["observed_gates"]["gaps"] == assessed["gaps"],"same_assessment_reasons")
                check(not candidate["business_reassessment_performed"],"no_reassessment")
                excluded_seen |= expected == "excluded"
                admitted_seen |= expected == "admitted"
            for visible in result["selected"]+result["review"]:
                check("_admitted" not in visible and "_rule_relevant" not in visible,"legacy_internal_keys_removed")
        saved.append(dict(phase=phase,input=payload,output=new))
    check(excluded_seen and admitted_seen,"fixtures_cover_admitted_and_excluded")
    bad=copy.deepcopy(prepared_input);bad["phase"]="evaluate";bad["cases"]=bad["cases"][:1]
    bad["cases"][0]["semantic_response"]=fixture_response(new_prepared["cases"][0])
    bad["cases"][0]["semantic_response"]["model"]="wrong-identity"
    for cp in classpaths:
        run, result=invoke(args.java,cp,bad)
        check(run.returncode != 0 and "artifact_identity_mismatch" in run.stderr,"invalid_protocol_still_fails")
    output=dict(assertions_passed=counter,synthetic_cases=7,phases=4,
                model_runs=0,real_accuracy_measurement=False,old_40_or_32_or_history_read=False,
                protocol_scores_are_fictional=True,
                mode="standalone_current_contract" if args.standalone else "explicit_baseline_comparison",
                legacy_outputs_unchanged=None if args.standalone else True,observations=saved)
    target=Path(args.output)
    with target.open("x",encoding="utf8") as handle:
        target.chmod(0o600);json.dump(output,handle,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in output.items() if k != "observations"},ensure_ascii=False))


if __name__ == "__main__":
    main()
