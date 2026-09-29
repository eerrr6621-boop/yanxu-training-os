"""Offline, fixed-budget synthetic contrastive fine-tuning pilot.
No private resumes, remote tracking, downloads, deployment or production writes.
Does not read holdout data or pick checkpoints using test-set performance.
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
import shutil
import sys
import time

os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                  TOKENIZERS_PARALLELISM="false", WANDB_DISABLED="true")
CONFIG = dict(seed=20260907, epochs=2, learning_rate=1e-5, weight_decay=0.01,
              temperature=0.05, accumulation=4, max_tokens=256, max_grad_norm=1.0,
              device="cpu", threads=1, max_train_seconds=600,
              checkpoint_selection="fixed final step; no evaluation-based selection")
BASE_MODEL="BAAI/bge-small-zh-v1.5"
BASE_REVISION="7999e1d3359715c523056ef9478215996d62a620"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


def rss():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/(1024*1024 if sys.platform=="darwin" else 1024)


def group_loss(scores,positive_count):
    import torch
    if not 0<positive_count<len(scores):
        raise ValueError("Need both positive and negative evidence")
    return torch.logsumexp(scores,dim=0)-torch.logsumexp(scores[:positive_count],dim=0)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--base-dir",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,required=True)
    args=parser.parse_args()
    root=args.output_dir.resolve()
    repo=Path(__file__).resolve().parent.parent
    if root.is_relative_to(repo) or "web" in root.parts:
        raise ValueError("Training artifacts must stay outside source/public directories")
    root.mkdir(parents=True,mode=0o700,exist_ok=False)
    from posttrain_pilot_data import build_dataset
    data=build_dataset()
    train,dev=data["train"],data["dev"]
    assert len(train)==80 and len(dev)==16
    assert not ({r["group"] for r in train}&{r["group"] for r in dev})
    assert len({r["query"] for r in train+dev})==96
    for row in train+dev:
        assert row["pos"] and len(row["neg"])>=3
        assert not set(row["pos"])&set(row["neg"])
    original=args.base_dir.resolve()/"original"
    base= json.loads((args.base_dir/"manifest.json").read_text())
    assert base["model"]==BASE_MODEL and base["revision"]==BASE_REVISION
    for name,value in base["source_sha256"].items():
        assert digest(original/name)==value, f"Base original mismatch: {name}"
    write(root/"data-snapshot.json",data)
    production={str(p.relative_to(repo)):digest(p) for p in (repo/"src/com/training").glob("*.java")}
    production["semantic/worker.py"]=digest(repo/"semantic/worker.py")
    frozen=dict(config=CONFIG,base_manifest=base,synthetic_data_only=True,real_resumes_used=False,
        training_data_sha256=digest(root/"data-snapshot.json"),
        training_source_sha256=digest(Path(__file__)),data_generator_sha256=digest(repo/"semantic/posttrain_pilot_data.py"),
        holdout_generator_sha256=digest(repo/"semantic/posttrain_holdout_cases.py"),
        frozen_at=datetime.now(timezone.utc).isoformat(),production_sources_sha256=production,
        holdout_read_before_training=False)
    write(root/"freeze.json",frozen)
    import torch
    from transformers import AutoModel,AutoTokenizer
    torch.set_num_threads(CONFIG["threads"])
    torch.set_num_interop_threads(1)
    torch.manual_seed(CONFIG["seed"])
    random.seed(CONFIG["seed"])
    torch.use_deterministic_algorithms(True)
    model=AutoModel.from_pretrained(original,local_files_only=True,use_safetensors=True,
                                   trust_remote_code=False,attn_implementation="eager").float()
    tokenizer=AutoTokenizer.from_pretrained(original,local_files_only=True,trust_remote_code=False)
    prefix="为这个句子生成表示以用于检索相关文章："
    batches=[]
    max_length=0
    for row in train:
        strings=[prefix+row["query"]]+row["pos"]+row["neg"]
        lengths=[len(tokenizer(s,add_special_tokens=True)["input_ids"]) for s in strings]
        max_length=max(max_length,max(lengths))
        if max(lengths)>CONFIG["max_tokens"]:
            raise ValueError("Training text would be truncated; refuse silent evidence loss")
        batches.append((row["id"],len(row["pos"]),tokenizer(strings,padding=True,truncation=False,return_tensors="pt")))
    parameter_count=sum(p.numel() for p in model.parameters())
    watched={name:p.detach().clone() for name,p in model.named_parameters()
             if name in ("encoder.layer.3.output.dense.weight","encoder.layer.0.attention.self.query.weight")}
    assert len(watched)==2
    optimizer=torch.optim.AdamW(model.parameters(),lr=CONFIG["learning_rate"],weight_decay=CONFIG["weight_decay"],foreach=False)
    model.train()
    started=time.monotonic()
    losses=[]
    updates=0
    optimizer.zero_grad(set_to_none=True)
    for epoch in range(CONFIG["epochs"]):
        order=list(range(len(batches)))
        random.Random(CONFIG["seed"]+epoch).shuffle(order)
        epoch_losses=[]
        for step,index in enumerate(order):
            if time.monotonic()-started>CONFIG["max_train_seconds"]:
                raise TimeoutError("Fixed training time budget exceeded; do not deploy incomplete pilot")
            _,positive_count,tokens=batches[index]
            states=model(**tokens).last_hidden_state
            embeddings=torch.nn.functional.normalize(states[:,0],p=2,dim=1)
            scores=(embeddings[1:] @ embeddings[0])/CONFIG["temperature"]
            loss=group_loss(scores,positive_count)
            if not torch.isfinite(loss):
                raise ValueError("Non-finite loss")
            (loss/CONFIG["accumulation"]).backward()
            epoch_losses.append(float(loss.detach()))
            if (step+1)%CONFIG["accumulation"]==0:
                norm=torch.nn.utils.clip_grad_norm_(model.parameters(),CONFIG["max_grad_norm"])
                if not torch.isfinite(norm):
                    raise ValueError("Non-finite gradient")
                optimizer.step();optimizer.zero_grad(set_to_none=True);updates+=1
            if (step+1)%20==0:
                print(json.dumps(dict(epoch=epoch+1,examples=step+1,optimizer_updates=updates,
                    mean_loss=sum(epoch_losses)/len(epoch_losses),elapsed_seconds=round(time.monotonic()-started,2),
                    peak_rss_mib=round(rss(),2))),flush=True)
        assert len(order)%CONFIG["accumulation"]==0
        losses.append(dict(epoch=epoch+1,mean_loss=sum(epoch_losses)/len(epoch_losses)))
    train_seconds=time.monotonic()-started
    changes={name:float((dict(model.named_parameters())[name].detach()-value).abs().mean()) for name,value in watched.items()}
    assert all(value>0 and math.isfinite(value) for value in changes.values()), "Optimizer did not update sampled model weights"
    checkpoint=root/"checkpoint"
    model.eval().save_pretrained(checkpoint,safe_serialization=True)
    tokenizer.save_pretrained(checkpoint)
    shutil.copyfile(original/"README.md",checkpoint/"BASE_MODEL_CARD.md")
    result=dict(completed=True,trained=True,deployed=False,synthetic_pilot_only=True,
        started_at=frozen["frozen_at"],finished_at=datetime.now(timezone.utc).isoformat(),
        parameter_count=parameter_count,architecture_added_parameters=0,examples=80,epochs=2,
        optimizer_updates=updates,training_seconds=round(train_seconds,3),peak_training_process_rss_mib=round(rss(),2),
        training_max_observed_tokens=max_length,losses=losses,
        checkpoint_sha256=digest(checkpoint/"model.safetensors"),base_weights_sha256=base["source_sha256"]["model.safetensors"],
        watched_parameter_mean_absolute_change=changes,
        checkpoints_differ=digest(checkpoint/"model.safetensors")!=base["source_sha256"]["model.safetensors"],
        platform=sys.platform,device="cpu",torch_version=torch.__version__,config=CONFIG,
        raw_rss_is_training_not_inference=True,holdout_used_for_training_or_selection=False)
    assert all(digest(repo/name)==value for name,value in production.items())
    assert digest(original/"model.safetensors")==base["source_sha256"]["model.safetensors"]
    write(root/"training-report.json",result)
    print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=="__main__":
    main()
