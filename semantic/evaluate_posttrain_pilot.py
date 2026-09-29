"""Isolated before/after inference benchmark; no production model allowlist changes.
Run each model/precision in a fresh process for comparable peak inference RSS.
Raw retrieval and current worker preprocessing are both diagnostic, NOT final
teacher eligibility or nationwide transport accuracy. Never selects a checkpoint.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import resource
import random
import sqlite3
import statistics
import sys
import time
from worker import Encoder,MODEL,REVISION


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class EvaluationEncoder(Encoder):
    def __init__(self,root,precision,training):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        manifest=json.loads((root/"manifest.json").read_text())
        if manifest.get("schema_version")=="yanxu-experimental-embedding-v1":
            assert manifest["base_model"]==MODEL and manifest["base_revision"]==REVISION
            assert manifest["production_approved"] is False
            assert manifest["trained_weights_sha256"]==training["checkpoint_sha256"]
        else:
            assert manifest["model"]==MODEL and manifest["revision"]==REVISION
        assert manifest["dimensions"]==512 and manifest["pooling"]=="CLS+L2" and manifest["max_tokens"]==512
        filename=f"model-{precision}.onnx"
        for name in (filename,"tokenizer.json"):
            assert sha(root/name)==manifest["sha256"][name]
        options=ort.SessionOptions()
        options.intra_op_num_threads=1;options.inter_op_num_threads=1
        options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL;options.enable_cpu_mem_arena=False
        self.session=ort.InferenceSession(str(root/filename),sess_options=options,providers=["CPUExecutionProvider"])
        self.tokenizer=Tokenizer.from_file(str(root/"tokenizer.json"));self.tokenizer.no_truncation();self.tokenizer.no_padding()
        self.np=np;self.version=manifest["sha256"][filename];self.precision=precision
        self.cache=sqlite3.connect(":memory:")
        self.cache.execute("CREATE TABLE vectors(k TEXT PRIMARY KEY,v BLOB NOT NULL,t INTEGER NOT NULL)")
        self.salt=os.urandom(32)
        self.actual_model=manifest["model"];self.actual_revision=manifest["revision"]
    def compare(self,payload):
        result=super().compare(payload)
        result.update(model=self.actual_model,revision=self.actual_revision,experimental_evaluation=True)
        return result


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--training-dir",type=Path,required=True)
    p.add_argument("--model-dir",type=Path,required=True)
    p.add_argument("--precision",choices=("fp32","int8"),required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    repo=Path(__file__).resolve().parent.parent
    if args.output.resolve().is_relative_to(repo) or "web" in args.output.resolve().parts:
        raise ValueError("Keep raw pilot evaluations outside source/public directories")
    if args.output.exists():
        raise ValueError("Do not overwrite evaluation results")
    training=json.loads((args.training_dir/"training-report.json").read_text())
    assert training["completed"] and training["trained"]
    freeze=json.loads((args.training_dir/"freeze.json").read_text())
    assert sha(repo/"semantic/posttrain_holdout_cases.py")==freeze["holdout_generator_sha256"]
    assert all(sha(repo/name)==value for name,value in freeze["production_sources_sha256"].items())
    # First held-out data access occurs AFTER the fixed final checkpoint exists.
    from posttrain_holdout_cases import build_holdout
    assert sha(args.training_dir/"data-snapshot.json")==freeze["training_data_sha256"]
    data=json.loads((args.training_dir/"data-snapshot.json").read_text())
    holdout=build_holdout()
    assert len(holdout)==32
    assert not {r["group"] for r in holdout}&{r["group"] for r in data["train"]+data["dev"]}
    train_text={text for r in data["train"] for text in [r["query"]]+r["pos"]+r["neg"]}
    assert not train_text & {text for r in holdout for text in [r["query"]]+r["pos"]+r["neg"]}
    frozen_test=json.dumps(holdout,ensure_ascii=False,sort_keys=True)
    started=time.monotonic()
    encoder=EvaluationEncoder(args.model_dir,args.precision,training)
    startup=time.monotonic()-started
    result=[]
    for split,rows in (("dev",data["dev"]),("holdout",holdout)):
        for row in rows:
            documents=[(s,True) for s in row["pos"]]+[(s,False) for s in row["neg"]]
            random.Random(int(hashlib.sha256(row["id"].encode()).hexdigest()[:12],16)).shuffle(documents)
            texts=[s for s,_ in documents]
            positives={i+1 for i,(_,positive) in enumerate(documents) if positive}
            encoder.cache.execute("DELETE FROM vectors");encoder.cache.commit()
            start=time.monotonic()
            q=encoder.encode(row["query"],query=True)
            scores=[float(encoder.np.dot(q,encoder.encode(text))) for text in texts]
            ranking=sorted(range(1,len(texts)+1),key=lambda ident:(-scores[ident-1],ident))
            raw_ms=(time.monotonic()-start)*1000
            score_pos=max(scores[i-1] for i in positives)
            score_neg=max(scores[i-1] for i in range(1,len(texts)+1) if i not in positives)
            entry=dict(id=row["id"],group=row["group"],split=split,raw_top1=ranking[0],
                raw_top1_correct=ranking[0] in positives,raw_positive_negative_margin=score_pos-score_neg,
                longest_passage_heuristic_correct=max(range(1,len(texts)+1),key=lambda ident:len(texts[ident-1])) in positives,
                raw_mean_reciprocal_rank=1/next(i+1 for i,ident in enumerate(ranking) if ident in positives),
                raw_ms=round(raw_ms,3),raw_scores=scores,worker_error=None)
            try:
                payload=dict(query=row["query"],documents=[dict(id=i+1,text=s) for i,s in enumerate(texts)])
                encoder.cache.execute("DELETE FROM vectors");encoder.cache.commit()
                start=time.monotonic();output=encoder.compare(payload)
                valid=[r for r in output["results"] if r["similarity"] is not None]
                valid.sort(key=lambda r:(-r["similarity"],r["id"]))
                entry.update(worker_top1=valid[0]["id"] if valid else None,
                    worker_top1_correct=bool(valid) and valid[0]["id"] in positives,
                    worker_cold_vector_cache_ms=round((time.monotonic()-start)*1000,3),
                    worker_positive_present=any(r["id"] in positives for r in valid))
                start=time.monotonic();warm=encoder.compare(payload)
                assert warm["results"]==output["results"]
                entry["worker_warm_cache_ms"]=round((time.monotonic()-start)*1000,3)
            except (ValueError,TimeoutError) as error:
                entry.update(worker_error=str(error),worker_top1=None,worker_top1_correct=False,worker_positive_present=False,
                    worker_cold_vector_cache_ms=None,worker_warm_cache_ms=None)
            result.append(entry)
    summary={}
    for split in ("dev","holdout"):
        rows=[r for r in result if r["split"]==split]
        summary[split]=dict(cases=len(rows),raw_top1_correct=sum(r["raw_top1_correct"] for r in rows),
            raw_mean_margin=statistics.mean(r["raw_positive_negative_margin"] for r in rows),
            raw_mrr=statistics.mean(r["raw_mean_reciprocal_rank"] for r in rows),
            worker_top1_correct=sum(r["worker_top1_correct"] for r in rows),
            longest_passage_heuristic_correct=sum(r["longest_passage_heuristic_correct"] for r in rows),
            worker_errors=sum(r["worker_error"] is not None for r in rows),
            raw_median_ms=statistics.median(r["raw_ms"] for r in rows),
            worker_cold_vector_cache_median_ms=statistics.median(r["worker_cold_vector_cache_ms"] for r in rows if r["worker_cold_vector_cache_ms"] is not None) if any(r["worker_cold_vector_cache_ms"] is not None for r in rows) else None,
            worker_warm_cache_median_ms=statistics.median(r["worker_warm_cache_ms"] for r in rows if r["worker_warm_cache_ms"] is not None) if any(r["worker_warm_cache_ms"] is not None for r in rows) else None,
            raw_failed_ids=[r["id"] for r in rows if not r["raw_top1_correct"]],
            worker_failed_ids=[r["id"] for r in rows if not r["worker_top1_correct"]])
    rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/(1024*1024 if sys.platform=="darwin" else 1024)
    report=dict(generated_at=datetime.now(timezone.utc).isoformat(),model=encoder.actual_model,revision=encoder.actual_revision,
        precision=args.precision,model_sha256=encoder.version,model_bytes=(args.model_dir/f"model-{args.precision}.onnx").stat().st_size,
        inference_process_peak_rss_mib=round(rss,3),startup_seconds=round(startup,3),platform=sys.platform,
        same_architecture=True,production_deployed=False,real_business_accuracy=False,
        holdout_sha256=hashlib.sha256(frozen_test.encode()).hexdigest(),summary=summary,results=result,
        metric_note="Raw and worker-preprocessed retrieval only; current eligibility rules are unchanged. Synthesized unseen topics, not real-resume blind validation; no checkpoint selection.")
    args.output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="results"},ensure_ascii=False),flush=True)


if __name__=="__main__":
    main()
