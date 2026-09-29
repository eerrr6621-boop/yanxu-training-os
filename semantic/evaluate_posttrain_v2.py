"""Fixed-checkpoint, identity-preserving offline benchmark.
Independent holdout is imported only after completed training is verified.
Retrieval, abstention and downstream evidence admission are separate metrics.
No checkpoint selection, model downloads, real resumes, DB or serving writes.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import random
import resource
import statistics
import subprocess
import sys
import time
from evaluate_posttrain_pilot import EvaluationEncoder, sha


def replay(cases, java, classpath, model, revision, precision, phase):
    payload=dict(cases=cases, expected_model=model, expected_revision=revision,
                 expected_precision=precision,phase=phase)
    run=subprocess.run([java,"-cp",classpath,"com.training.PosttrainingV2Probe"],
        input=json.dumps(payload,ensure_ascii=False),capture_output=True,text=True,timeout=120,check=True)
    return json.loads(run.stdout)["cases"]


def summarize(rows):
    positive=[r for r in rows if r["positive_count"]]
    none=[r for r in rows if not r["positive_count"]]
    groups={}
    for r in rows:groups.setdefault(r["family"],[]).append(r)
    pairs=[g for g in groups.values() if len(g)>1]
    def count(field,items):return sum(bool(r.get(field)) for r in items)
    def median(field):
        values=[r[field] for r in rows if r.get(field) is not None]
        return round(statistics.median(values),3) if values else None
    return dict(cases=len(rows),positive_cases=len(positive),no_match_cases=len(none),
        raw_top1_correct=count("raw_top1_correct",positive),worker_top1_correct=count("worker_top1_correct",positive),
        worker_positive_present=count("worker_positive_present",positive),
        raw_mrr=statistics.mean(r["raw_mrr"] for r in positive) if positive else None,
        longest_heuristic_correct=count("longest_heuristic_correct",positive),
        worker_no_match_abstained=count("worker_abstained",none),
        pipeline_positive_admitted=count("pipeline_positive_admitted",positive),
        pipeline_top1_correct=count("pipeline_top1_correct",positive),
        pipeline_negative_admission_cases=sum(bool(r.get("pipeline_wrongly_admitted_ids")) for r in rows),
        pipeline_no_match_no_auto_admission=sum(not r.get("worker_error") and bool(r.get("pipeline_no_admission")) for r in none),
        pipeline_no_match_rejected=sum(not r.get("worker_error") and bool(r.get("pipeline_no_admission")) and not r.get("pipeline_has_review") for r in none),
        pipeline_no_match_pending_review=sum(not r.get("worker_error") and bool(r.get("pipeline_no_admission")) and bool(r.get("pipeline_has_review")) for r in none),
        pipeline_no_match_error=count("worker_error",none),
        pipeline_evaluated_cases=sum(r.get("pipeline_admitted_ids") is not None for r in rows),
        worker_errors=count("worker_error",rows),paired_families=len(pairs),
        worker_same_top1_family_count=sum(all(not r.get("worker_error") and r.get("worker_top1") is not None for r in g) and len({r.get("worker_top1") for r in g})==1 for g in pairs),
        worker_all_abstained_family_count=sum(all(not r.get("worker_error") and r.get("worker_abstained") for r in g) for g in pairs),
        raw_median_ms=median("raw_ms"),worker_cold_median_ms=median("worker_cold_ms"),worker_warm_median_ms=median("worker_warm_ms"),
        raw_failed_ids=[r["id"] for r in positive if not r["raw_top1_correct"]],
        worker_failed_ids=[r["id"] for r in positive if not r["worker_top1_correct"]],
        pipeline_missed_positive_ids=[r["id"] for r in positive if not r.get("pipeline_positive_admitted")],
        pipeline_negative_admission_ids=[r["id"] for r in rows if r.get("pipeline_wrongly_admitted_ids")])


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--v2-training-dir",type=Path,required=True)
    p.add_argument("--artifact-training-dir",type=Path)
    p.add_argument("--model-dir",type=Path,required=True)
    p.add_argument("--precision",choices=("int8","fp32"),default="int8")
    p.add_argument("--java",required=True)
    p.add_argument("--classpath",required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args();repo=Path(__file__).resolve().parent.parent
    class_root=Path(args.classpath.split(os.pathsep)[0]).resolve()
    assert (class_root/"com/training/PosttrainingV2Probe.class").is_file()
    classes={str(f.relative_to(class_root)):sha(f) for f in (class_root/"com/training").glob("*.class")}
    if args.output.resolve().is_relative_to(repo) or args.output.exists():raise ValueError("New private report required")
    trained=json.loads((args.v2_training_dir/"training-report.json").read_text())
    frozen=json.loads((args.v2_training_dir/"freeze.json").read_text())
    assert trained["completed"] and trained["trained"] and not trained["deployed"]
    assert trained["parent_checkpoint_sha256"]==frozen["parent_checkpoint_sha256"]
    assert trained["config"]==frozen["config"]
    assert trained["optimizer_updates"]==trained["config"]["epochs"]*trained["examples"]//trained["config"]["accumulation"]
    assert sha(args.v2_training_dir/"checkpoint/config.json")==trained["checkpoint_config_sha256"]
    assert all(sha(repo/"semantic"/n)==s for n,s in frozen["helper_sources_sha256"].items())
    assert sha(args.v2_training_dir/"checkpoint/model.safetensors")==trained["checkpoint_sha256"]
    assert sha(args.v2_training_dir/"data-snapshot.json")==frozen["training_data_sha256"]
    assert sha(repo/"semantic/posttrain_v2_holdout.py")==frozen["holdout_generator_sha256"]
    assert all(sha(repo/n)==s for n,s in frozen["production_sources_sha256"].items())
    # Only this point imports unseen test questions, after the final weights exist.
    from posttrain_v2_holdout import build_holdout
    from posttrain_holdout_cases import build_holdout as old_holdout
    data=json.loads((args.v2_training_dir/"data-snapshot.json").read_text())
    assert sha(args.v2_training_dir/"ancestor-data-snapshot.json")==frozen["ancestor_training_data_sha256"]
    ancestor=json.loads((args.v2_training_dir/"ancestor-data-snapshot.json").read_text())
    fresh=build_holdout();old=old_holdout()
    assert len(fresh)==40 and sum(not r["pos"] for r in fresh)==8
    assert not {r["group"] for r in fresh}&{r["group"] for r in data["train"]+data["dev"]}
    train_text={t for r in data["train"]+data["dev"]+ancestor["train"]+ancestor["dev"] for t in [r["query"]]+r["pos"]+r["neg"]}
    assert not train_text&{t for r in fresh for t in [r["query"]]+r["pos"]+r["neg"]}
    artifact=json.loads((args.artifact_training_dir/"training-report.json").read_text()) if args.artifact_training_dir else trained
    started=time.monotonic();encoder=EvaluationEncoder(args.model_dir,args.precision,artifact)
    startup=time.monotonic()-started
    test_rows=[];cases=[]
    for split,rows in (("dev",data["dev"]),("fresh_holdout",fresh),("reused_diagnostic",old)):
        for original in rows:
            row=dict(original);row["family"]=row.get("family",row["id"]);row["split"]=split
            docs=[(s,True) for s in row["pos"]]+[(s,False) for s in row["neg"]]
            # One order per factual family; paraphrases do not get different tie breaks.
            random.Random(int(hashlib.sha256(row["family"].encode()).hexdigest()[:12],16)).shuffle(docs)
            row["documents"]=docs;test_rows.append(row)
            cases.append(dict(id=row["id"],query=row["query"],documents=[dict(id=i+1,text=t) for i,(t,_) in enumerate(docs)]))
    prepared=replay(cases,args.java,args.classpath,encoder.actual_model,encoder.actual_revision,args.precision,"prepare")
    assert [r["id"] for r in prepared]==[r["id"] for r in test_rows]
    results=[];replay_cases=[]
    for row,test,prep in zip(test_rows,cases,prepared):
        docs=row["documents"];texts=[t for t,_ in docs];positive={i+1 for i,(_,yes) in enumerate(docs) if yes}
        encoder.cache.execute("DELETE FROM vectors");encoder.cache.commit();start=time.monotonic()
        q=encoder.encode(row["query"],query=True);scores=[float(encoder.np.dot(q,encoder.encode(t))) for t in texts]
        ranking=sorted(range(1,len(texts)+1),key=lambda i:(-scores[i-1],i))
        entry=dict(id=row["id"],family=row["family"],group=row["group"],split=row["split"],positive_count=len(positive),
            positive_ids=sorted(positive),raw_scores=scores,raw_top1=ranking[0],raw_top1_correct=ranking[0] in positive,
            raw_mrr=1/next(i+1 for i,x in enumerate(ranking) if x in positive) if positive else None,
            longest_heuristic_correct=max(range(1,len(texts)+1),key=lambda i:len(texts[i-1])) in positive,
            raw_ms=round((time.monotonic()-start)*1000,3),worker_error=None)
        payload=dict(query=prep["focused_query"],documents=prep["prepared_documents"])
        encoder.cache.execute("DELETE FROM vectors");encoder.cache.commit()
        try:
            start=time.monotonic();out=encoder.compare(payload);cold=(time.monotonic()-start)*1000
            valid=sorted((r for r in out["results"] if r["similarity"] is not None),key=lambda r:(-r["similarity"],r["id"]))
            start=time.monotonic();warm=encoder.compare(payload);warm_ms=(time.monotonic()-start)*1000
            assert {k:v for k,v in warm.items() if k!="elapsed_ms"}=={k:v for k,v in out.items() if k!="elapsed_ms"}
            entry.update(worker_top1=valid[0]["id"] if valid else None,worker_top1_correct=bool(valid) and valid[0]["id"] in positive,
                worker_positive_present=any(r["id"] in positive for r in valid),worker_abstained=not valid,
                worker_cold_ms=round(cold,3),worker_warm_ms=round(warm_ms,3))
            replay_cases.append(dict(test,semantic_response=out))
        except (ValueError,TimeoutError) as error:
            entry.update(worker_error=f"{type(error).__name__}: {error}",worker_top1=None,worker_top1_correct=False,worker_positive_present=False,worker_abstained=False)
            replay_cases.append(dict(test,semantic_error="evaluation_worker_failed"))
        results.append(entry)
    pipeline=replay(replay_cases,args.java,args.classpath,encoder.actual_model,encoder.actual_revision,args.precision,"evaluate")
    assert [r["id"] for r in pipeline]==[r["id"] for r in results]
    for row,out in zip(results,pipeline):
        assert row["id"]==out["id"]
        positive=set(row["positive_ids"]);admitted=out["admitted_ids"];selected=out["selected"]
        if not row["worker_error"]:assert out["semantic_state"]["status"]=="ready",out["semantic_state"]
        row.update(pipeline_admitted_ids=admitted,pipeline_wrongly_admitted_ids=[i for i in admitted if i not in positive],
            pipeline_positive_admitted=bool(positive&set(admitted)),pipeline_no_admission=not admitted,
            pipeline_has_review=bool(out["review"]),pipeline_top1_correct=bool(selected) and selected[0]["teacher_id"] in positive,
            pipeline_selected_ids=[r["teacher_id"] for r in selected],pipeline_semantic_state=out["semantic_state"])
    rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/(1024*1024 if sys.platform=="darwin" else 1024)
    report=dict(generated_at=datetime.now(timezone.utc).isoformat(),model=encoder.actual_model,revision=encoder.actual_revision,
        precision=args.precision,model_sha256=encoder.version,model_bytes=(args.model_dir/f"model-{args.precision}.onnx").stat().st_size,
        inference_process_peak_rss_mib=round(rss,3),startup_seconds=round(startup,3),
        final_checkpoint_before_holdout=True,production_deployed=False,real_business_accuracy=False,
        fresh_holdout_sha256=sha(repo/"semantic/posttrain_v2_holdout.py"),worker_sha256=sha(repo/"semantic/worker.py"),
        evaluator_sha256=sha(Path(__file__)),probe_sha256=sha(repo/"scripts/PosttrainingV2Probe.java"),
        executed_java_classes_sha256=classes,
        summary={s:summarize([r for r in results if r["split"]==s]) for s in ("dev","fresh_holdout","reused_diagnostic")},
        results=results,
        metric_note="Synthetic family-heldout retrieval and offline downstream replay; NOT production HTTP validation or real-resume accuracy. No-match has no raw embedding threshold. Reused diagnostic is not blind. Production model registry unchanged.")
    assert all(sha(repo/n)==s for n,s in frozen["production_sources_sha256"].items())
    assert all(sha(class_root/n)==s for n,s in classes.items())
    args.output.parent.mkdir(parents=True,mode=0o700,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k not in ("results","executed_java_classes_sha256")},ensure_ascii=False),flush=True)


if __name__=="__main__":main()
