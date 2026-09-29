"""Offline continuation on new balanced synthetic families. Fixed final checkpoint.
Never imports holdout, reads real resumes, downloads, or changes serving models.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import random
import shutil
import time

os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                  TOKENIZERS_PARALLELISM="false", WANDB_DISABLED="true")
from train_posttrain_pilot import digest, write, rss, group_loss, BASE_MODEL, BASE_REVISION

CONFIG = dict(seed=20260908, epochs=4, learning_rate=5e-6, weight_decay=0.01,
              temperature=0.05, accumulation=4, max_tokens=256, max_grad_norm=1.0,
              device="cpu", threads=1, max_train_seconds=900,
              checkpoint_selection="fixed final step, no dev/test checkpoint selection")


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--base-dir", type=Path, required=True)
    p.add_argument("--parent-training-dir", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    args=p.parse_args()
    repo=Path(__file__).resolve().parent.parent
    root=args.output_dir.resolve()
    if root.is_relative_to(repo) or any(x in root.parts for x in ("web","public","static")):
        raise ValueError("Use a new private output outside source/public directories")
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    from posttrain_v2_data import build_dataset
    data=build_dataset()
    train,dev=data["train"],data["dev"]
    assert train and dev and len(train)%CONFIG["accumulation"]==0
    assert not {r["family"] for r in train}&{r["family"] for r in dev}
    assert not {r["group"] for r in train}&{r["group"] for r in dev}
    assert len({r["query"] for r in train+dev})==len(train)+len(dev)
    for row in train+dev:
        assert row["pos"] and len(row["neg"])>=3 and not set(row["pos"])&set(row["neg"])
    parent=json.loads((args.parent_training_dir/"training-report.json").read_text())
    parent_freeze=json.loads((args.parent_training_dir/"freeze.json").read_text())
    checkpoint=args.parent_training_dir/"checkpoint"
    assert parent["completed"] and parent["trained"] and not parent["deployed"]
    assert digest(checkpoint/"model.safetensors")==parent["checkpoint_sha256"]
    base=json.loads((args.base_dir/"manifest.json").read_text())
    assert base["model"]==BASE_MODEL and base["revision"]==BASE_REVISION
    for name,sha in base["source_sha256"].items():
        assert digest(args.base_dir/"original"/name)==sha
    assert parent["base_weights_sha256"]==base["source_sha256"]["model.safetensors"]
    assert parent_freeze["base_manifest"]==base
    assert digest(args.parent_training_dir/"data-snapshot.json")==parent_freeze["training_data_sha256"]
    # Serialization metadata may differ; architecture and training-affecting settings may not.
    ignored={"_name_or_path","transformers_version","torch_dtype","dtype"}
    parent_config=json.loads((checkpoint/"config.json").read_text())
    base_config=json.loads((args.base_dir/"original/config.json").read_text())
    assert {k:v for k,v in parent_config.items() if k not in ignored}=={k:v for k,v in base_config.items() if k not in ignored}
    assert digest(repo/"semantic/train_posttrain_pilot.py")==parent_freeze["training_source_sha256"]
    write(root/"data-snapshot.json",data)
    shutil.copyfile(args.parent_training_dir/"data-snapshot.json",root/"ancestor-data-snapshot.json")
    production={str(f.relative_to(repo)):digest(f) for f in (repo/"src/com/training").glob("*.java")}
    production["semantic/worker.py"]=digest(repo/"semantic/worker.py")
    # Hashing opaque bytes seals the independent test without importing its cases.
    helper_sources={n:digest(repo/"semantic"/n) for n in ("train_posttrain_pilot.py","export_posttrain_pilot.py","evaluate_posttrain_pilot.py")}
    frozen=dict(config=CONFIG, base_manifest=base, parent_checkpoint_sha256=parent["checkpoint_sha256"],
                parent_config_sha256=digest(checkpoint/"config.json"),
                parent_report_sha256=digest(args.parent_training_dir/"training-report.json"),
                ancestor_training_data_sha256=digest(root/"ancestor-data-snapshot.json"),
                helper_sources_sha256=helper_sources,
                training_data_sha256=digest(root/"data-snapshot.json"),
                training_source_sha256=digest(Path(__file__)),
                data_generator_sha256=digest(repo/"semantic/posttrain_v2_data.py"),
                holdout_generator_sha256=digest(repo/"semantic/posttrain_v2_holdout.py"),
                production_sources_sha256=production, synthetic_data_only=True, real_resumes_used=False,
                holdout_read_before_training=False, frozen_at=datetime.now(timezone.utc).isoformat())
    write(root/"freeze.json",frozen)
    import torch
    from transformers import AutoModel,AutoTokenizer
    torch.set_num_threads(1);torch.set_num_interop_threads(1)
    torch.manual_seed(CONFIG["seed"]);random.seed(CONFIG["seed"])
    torch.use_deterministic_algorithms(True)
    model=AutoModel.from_pretrained(checkpoint,local_files_only=True,use_safetensors=True,
        trust_remote_code=False,attn_implementation="eager").float()
    tokenizer=AutoTokenizer.from_pretrained(args.base_dir/"original",local_files_only=True,trust_remote_code=False)
    batches=[];max_length=0;length_positions={"shortest":0,"middle":0,"longest":0,"tied":0}
    for row in train:
        strings=["为这个句子生成表示以用于检索相关文章："+row["query"]]+row["pos"]+row["neg"]
        lengths=[len(tokenizer(s,add_special_tokens=True)["input_ids"]) for s in strings]
        max_length=max(max_length,max(lengths))
        if max(lengths)>CONFIG["max_tokens"]: raise ValueError("Refuse truncating training evidence")
        pl=lengths[1];nl=lengths[1+len(row["pos"]):]
        position="tied" if pl in nl else "shortest" if pl<min(nl) else "longest" if pl>max(nl) else "middle"
        length_positions[position]+=1
        batches.append((len(row["pos"]),tokenizer(strings,padding=True,truncation=False,return_tensors="pt")))
    assert all(length_positions[k]>len(train)*.15 for k in ("shortest","middle","longest")), "Token-length shortcut is not balanced"
    parameter_count=sum(p.numel() for p in model.parameters())
    assert parameter_count==parent["parameter_count"]
    watched={name:p.detach().clone() for name,p in model.named_parameters()
             if name in ("encoder.layer.3.output.dense.weight","encoder.layer.0.attention.self.query.weight")}
    optimizer=torch.optim.AdamW(model.parameters(),lr=CONFIG["learning_rate"],weight_decay=CONFIG["weight_decay"],foreach=False)
    model.train();started=time.monotonic();losses=[];updates=0
    optimizer.zero_grad(set_to_none=True)
    for epoch in range(CONFIG["epochs"]):
        order=list(range(len(batches)));random.Random(CONFIG["seed"]+epoch).shuffle(order)
        values=[]
        for step,index in enumerate(order):
            if time.monotonic()-started>CONFIG["max_train_seconds"]: raise TimeoutError("Training budget exceeded; incomplete model not deployable")
            count,tokens=batches[index]
            states=model(**tokens).last_hidden_state
            emb=torch.nn.functional.normalize(states[:,0],p=2,dim=1)
            loss=group_loss((emb[1:]@emb[0])/CONFIG["temperature"],count)
            if not torch.isfinite(loss): raise ValueError("Non-finite loss")
            (loss/CONFIG["accumulation"]).backward();values.append(float(loss.detach()))
            if (step+1)%CONFIG["accumulation"]==0:
                norm=torch.nn.utils.clip_grad_norm_(model.parameters(),CONFIG["max_grad_norm"])
                if not torch.isfinite(norm): raise ValueError("Non-finite gradient")
                optimizer.step();optimizer.zero_grad(set_to_none=True);updates+=1
            if (step+1)%32==0:
                print(json.dumps(dict(epoch=epoch+1,examples=step+1,optimizer_updates=updates,
                    loss=sum(values)/len(values),elapsed_seconds=round(time.monotonic()-started,2),peak_rss_mib=round(rss(),2))),flush=True)
        losses.append(dict(epoch=epoch+1,mean_loss=sum(values)/len(values)))
    seconds=time.monotonic()-started
    assert updates==CONFIG["epochs"]*len(train)//CONFIG["accumulation"]
    changes={n:float((dict(model.named_parameters())[n].detach()-v).abs().mean()) for n,v in watched.items()}
    assert all(math.isfinite(v) and v>0 for v in changes.values())
    out=root/"checkpoint";model.eval().save_pretrained(out,safe_serialization=True);tokenizer.save_pretrained(out)
    shutil.copyfile(args.base_dir/"original/README.md",out/"BASE_MODEL_CARD.md")
    result=dict(completed=True,trained=True,deployed=False,synthetic_pilot_only=True,experiment="balanced-augmentation-v2",
                started_at=frozen["frozen_at"],finished_at=datetime.now(timezone.utc).isoformat(),
                parameter_count=parameter_count,architecture_added_parameters=0,examples=len(train),dev_examples=len(dev),
                independent_training_families=len({r["family"] for r in train}),epochs=CONFIG["epochs"],optimizer_updates=updates,
                training_seconds=round(seconds,3),peak_training_process_rss_mib=round(rss(),2),losses=losses,
                training_max_observed_tokens=max_length,positive_token_length_positions=length_positions,
                checkpoint_sha256=digest(out/"model.safetensors"),checkpoint_config_sha256=digest(out/"config.json"),
                parent_checkpoint_sha256=parent["checkpoint_sha256"],
                base_weights_sha256=base["source_sha256"]["model.safetensors"],watched_parameter_mean_absolute_change=changes,
                holdout_used_for_training_or_selection=False,config=CONFIG)
    assert all(digest(repo/n)==v for n,v in production.items())
    assert digest(repo/"semantic/posttrain_v2_holdout.py")==frozen["holdout_generator_sha256"]
    assert digest(checkpoint/"model.safetensors")==parent["checkpoint_sha256"]
    assert digest(checkpoint/"config.json")==frozen["parent_config_sha256"]
    assert digest(Path(__file__))==frozen["training_source_sha256"]
    assert all(digest(repo/"semantic"/n)==s for n,s in helper_sources.items())
    write(root/"training-report.json",result);print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=="__main__":main()
