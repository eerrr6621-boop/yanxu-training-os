"""Fixed, gentle offline continuation; no private resumes or held-out questions.

Freeze the lower layers and anchor new embeddings to the unmodified parent.
This is an experiment, never automatic serving-model replacement.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import random
import shutil
import sys
import time

os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                  TOKENIZERS_PARALLELISM="false", WANDB_DISABLED="true")
from train_posttrain_pilot import digest, write, rss, group_loss, BASE_MODEL, BASE_REVISION

EXPERIMENT="anchored-top-layer-v3"
CONFIG=dict(seed=20260909, epochs=2, learning_rate=1e-6, weight_decay=0.01,
    temperature=0.05, accumulation=4, max_tokens=256, max_grad_norm=1.0,
    retention_weight=10.0, trainable_prefix="encoder.layer.3.", dropout=False,
    device="cpu", threads=1, max_train_seconds=900,
    checkpoint_selection="fixed final step; no dev/test checkpoint selection")
TOKENIZER_FILES=("tokenizer.json","tokenizer_config.json","special_tokens_map.json")


def main():
    if sys.flags.optimize:raise RuntimeError("Run without -O; experiment assertions are mandatory")
    p=argparse.ArgumentParser()
    p.add_argument("--base-dir",type=Path,required=True)
    p.add_argument("--parent-training-dir",type=Path,required=True)
    p.add_argument("--output-dir",type=Path,required=True)
    p.add_argument("--evaluation-classpath",required=True)
    args=p.parse_args();repo=Path(__file__).resolve().parent.parent;root=args.output_dir.resolve()
    if root.is_relative_to(repo) or any(x in root.parts for x in ("web","public","static")):
        raise ValueError("New private output outside repository/public directories required")
    from posttrain_v3_data import build_dataset, validate
    from evaluate_posttrain_v3 import capture_classpath
    from posttrain_runtime_manifest import capture_runtime
    validate();data=build_dataset();train,dev=data["train"],data["dev"]
    assert len(train)%CONFIG["accumulation"]==0
    parent_dir=args.parent_training_dir
    parent=json.loads((parent_dir/"training-report.json").read_text())
    parent_freeze=json.loads((parent_dir/"freeze.json").read_text())
    checkpoint=parent_dir/"checkpoint"
    assert parent["completed"] and parent["trained"] and not parent["deployed"]
    assert digest(checkpoint/"model.safetensors")==parent["checkpoint_sha256"]
    base=json.loads((args.base_dir/"manifest.json").read_text())
    assert base["model"]==BASE_MODEL and base["revision"]==BASE_REVISION
    for name,sha in base["source_sha256"].items():
        assert digest(args.base_dir/"original"/name)==sha
    assert parent["base_weights_sha256"]==base["source_sha256"]["model.safetensors"]
    assert parent_freeze["base_manifest"]==base
    assert digest(parent_dir/"data-snapshot.json")==parent_freeze["training_data_sha256"]
    ignored={"_name_or_path","transformers_version","torch_dtype","dtype"}
    parent_config=json.loads((checkpoint/"config.json").read_text())
    base_config=json.loads((args.base_dir/"original/config.json").read_text())
    assert {k:v for k,v in parent_config.items() if k not in ignored}=={k:v for k,v in base_config.items() if k not in ignored}
    assert digest(repo/"semantic/train_posttrain_pilot.py")==parent_freeze["training_source_sha256"]
    root.mkdir(mode=0o700,parents=True,exist_ok=False)
    write(root/"data-snapshot.json",data)
    shutil.copyfile(parent_dir/"data-snapshot.json",root/"ancestor-data-snapshot.json")
    production={str(f.relative_to(repo)):digest(f) for f in (repo/"src/com/training").glob("*.java")}
    production.update({n:digest(repo/n) for n in ("semantic/worker.py","scripts/PosttrainingV2Probe.java")})
    helpers={n:digest(repo/"semantic"/n) for n in (
        "evaluate_posttrain_v3.py","export_posttrain_v3.py","evaluate_posttrain_v2.py",
        "evaluate_posttrain_pilot.py","export_posttrain_pilot.py","train_posttrain_pilot.py",
        "posttrain_v2_holdout.py","posttrain_holdout_cases.py",
        "test_posttrain_v3_pipeline_validation.py","test_posttrain_v3_data.py",
        "test_posttrain_v3_holdout_validation.py","replay_private_resume_history.py",
        "posttrain_runtime_manifest.py","test_posttrain_runtime_manifest.py",
        "requirements.txt","requirements-build.txt")}
    runtime=capture_runtime();write(root/"runtime-snapshot.json",runtime)
    expected_updates=CONFIG["epochs"]*len(train)//CONFIG["accumulation"]
    frozen=dict(experiment=EXPERIMENT,config=CONFIG,expected_optimizer_updates=expected_updates,
        trainable_parameter_prefix=CONFIG["trainable_prefix"],
        runtime_manifest_sha256=digest(root/"runtime-snapshot.json"),
        base_manifest=base,parent_checkpoint_sha256=parent["checkpoint_sha256"],
        parent_checkpoint_config_sha256=digest(checkpoint/"config.json"),
        parent_report_sha256=digest(parent_dir/"training-report.json"),
        parent_freeze_sha256=digest(parent_dir/"freeze.json"),
        ancestor_training_data_sha256=digest(root/"ancestor-data-snapshot.json"),
        helper_sources_sha256=helpers,training_data_sha256=digest(root/"data-snapshot.json"),
        training_source_sha256=digest(Path(__file__)),
        data_generator_sha256=digest(repo/"semantic/posttrain_v3_data.py"),
        holdout_generator_sha256=digest(repo/"semantic/posttrain_v3_holdout.py"),
        production_sources_sha256=production,
        evaluation_classpath_sha256=capture_classpath(args.evaluation_classpath),
        synthetic_data_only=True,real_resumes_used=False,holdout_read_before_training=False,
        frozen_at=datetime.now(timezone.utc).isoformat())
    write(root/"freeze.json",frozen)
    import torch
    from transformers import AutoModel,AutoTokenizer
    torch.set_num_threads(1);torch.set_num_interop_threads(1)
    torch.manual_seed(CONFIG["seed"]);random.seed(CONFIG["seed"])
    torch.use_deterministic_algorithms(True)
    model=AutoModel.from_pretrained(checkpoint,local_files_only=True,use_safetensors=True,
        trust_remote_code=False,attn_implementation="eager").float()
    tokenizer=AutoTokenizer.from_pretrained(args.base_dir/"original",local_files_only=True,trust_remote_code=False)
    for name,param in model.named_parameters():param.requires_grad_(name.startswith(CONFIG["trainable_prefix"]))
    trainable=[p for p in model.parameters() if p.requires_grad]
    assert trainable and len(trainable)<len(list(model.parameters()))
    frozen_params={n:p.detach().clone() for n,p in model.named_parameters() if not p.requires_grad}
    watched={n:p.detach().clone() for n,p in model.named_parameters()
             if n=="encoder.layer.3.output.dense.weight"}
    batches=[];max_length=0;length_positions={"shortest":0,"middle":0,"longest":0,"tied":0}
    model.eval();started=time.monotonic()
    # Cache the unchanged parent's representations. No second trainable teacher,
    # no external corpus, and no test cases enter the retention objective.
    for row in train:
        strings=["为这个句子生成表示以用于检索相关文章："+row["query"]]+row["pos"]+row["neg"]
        lengths=[len(tokenizer(s,add_special_tokens=True)["input_ids"]) for s in strings]
        max_length=max(max_length,max(lengths))
        if max(lengths)>CONFIG["max_tokens"]:raise ValueError("Refuse truncating factual evidence")
        pl=lengths[1];nl=lengths[1+len(row["pos"]):]
        pos="tied" if pl in nl else "shortest" if pl<min(nl) else "longest" if pl>max(nl) else "middle"
        length_positions[pos]+=1
        tokens=tokenizer(strings,padding=True,truncation=False,return_tensors="pt")
        with torch.no_grad():
            anchor=torch.nn.functional.normalize(model(**tokens).last_hidden_state[:,0],p=2,dim=1).detach()
        batches.append((len(row["pos"]),tokens,anchor))
    assert all(length_positions[k]>=len(train)*.2 for k in ("shortest","middle","longest"))
    parameter_count=sum(p.numel() for p in model.parameters())
    assert parameter_count==parent["parameter_count"]
    optimizer=torch.optim.AdamW(trainable,lr=CONFIG["learning_rate"],weight_decay=CONFIG["weight_decay"],foreach=False)
    # eval() deliberately disables dropout while preserving autograd.
    optimizer.zero_grad(set_to_none=True);losses=[];updates=0;gradient_norms=[]
    for epoch in range(CONFIG["epochs"]):
        order=list(range(len(batches)));random.Random(CONFIG["seed"]+epoch).shuffle(order)
        values=[];retrieval_values=[];retention_values=[]
        for step,index in enumerate(order):
            if time.monotonic()-started>CONFIG["max_train_seconds"]:raise TimeoutError("Incomplete run is not deployable")
            count,tokens,anchor=batches[index]
            emb=torch.nn.functional.normalize(model(**tokens).last_hidden_state[:,0],p=2,dim=1)
            retrieval=group_loss((emb[1:]@emb[0])/CONFIG["temperature"],count)
            retention=(1-(emb*anchor).sum(dim=1)).clamp_min(0).mean()
            loss=retrieval+CONFIG["retention_weight"]*retention
            if not torch.isfinite(loss):raise ValueError("Non-finite loss")
            (loss/CONFIG["accumulation"]).backward()
            values.append(float(loss.detach()));retrieval_values.append(float(retrieval.detach()));retention_values.append(float(retention.detach()))
            if (step+1)%CONFIG["accumulation"]==0:
                norm=torch.nn.utils.clip_grad_norm_(trainable,CONFIG["max_grad_norm"])
                if not torch.isfinite(norm) or float(norm)<=0:raise ValueError("Non-finite or zero gradient")
                gradient_norms.append(float(norm))
                optimizer.step();optimizer.zero_grad(set_to_none=True);updates+=1
            if (step+1)%32==0:
                print(json.dumps(dict(epoch=epoch+1,examples=step+1,optimizer_updates=updates,
                    loss=sum(values)/len(values),elapsed_seconds=round(time.monotonic()-started,2),peak_rss_mib=round(rss(),2))),flush=True)
        losses.append(dict(epoch=epoch+1,mean_loss=sum(values)/len(values),
            retrieval_loss=sum(retrieval_values)/len(values),retention_loss=sum(retention_values)/len(values)))
    assert updates==expected_updates==len(gradient_norms)
    current=dict(model.named_parameters())
    assert all(torch.equal(current[n].detach(),v) for n,v in frozen_params.items())
    changes={n:float((current[n].detach()-v).abs().mean()) for n,v in watched.items()}
    assert changes and all(math.isfinite(v) and v>0 for v in changes.values())
    out=root/"checkpoint";model.save_pretrained(out,safe_serialization=True);tokenizer.save_pretrained(out)
    shutil.copyfile(args.base_dir/"original/README.md",out/"BASE_MODEL_CARD.md")
    report=dict(completed=True,trained=True,deployed=False,synthetic_pilot_only=True,experiment=EXPERIMENT,
        started_at=frozen["frozen_at"],finished_at=datetime.now(timezone.utc).isoformat(),config=CONFIG,
        parameter_count=parameter_count,trainable_parameter_count=sum(p.numel() for p in trainable),
        trainable_parameter_prefix=CONFIG["trainable_prefix"],
        runtime_manifest_sha256=frozen["runtime_manifest_sha256"],
        nonzero_gradient_updates=len(gradient_norms),minimum_preclip_gradient_norm=min(gradient_norms),
        maximum_preclip_gradient_norm=max(gradient_norms),
        frozen_parameters_unchanged=True,architecture_added_parameters=0,
        examples=len(train),dev_examples=len(dev),independent_training_families=len({r["family"] for r in train}),
        epochs=CONFIG["epochs"],optimizer_updates=updates,expected_optimizer_updates=expected_updates,
        training_seconds=round(time.monotonic()-started,3),peak_training_process_rss_mib=round(rss(),2),
        losses=losses,training_max_observed_tokens=max_length,positive_token_length_positions=length_positions,
        checkpoint_sha256=digest(out/"model.safetensors"),checkpoint_config_sha256=digest(out/"config.json"),
        checkpoint_tokenizer_sha256={n:digest(out/n) for n in TOKENIZER_FILES},
        parent_checkpoint_sha256=parent["checkpoint_sha256"],
        parent_checkpoint_config_sha256=frozen["parent_checkpoint_config_sha256"],
        base_weights_sha256=base["source_sha256"]["model.safetensors"],
        watched_parameter_mean_absolute_change=changes,holdout_used_for_training_or_selection=False)
    assert all(digest(repo/n)==s for n,s in production.items())
    assert all(digest(repo/"semantic"/n)==s for n,s in helpers.items())
    assert digest(repo/"semantic/posttrain_v3_holdout.py")==frozen["holdout_generator_sha256"]
    assert digest(repo/"semantic/posttrain_v3_data.py")==frozen["data_generator_sha256"]
    assert digest(Path(__file__))==frozen["training_source_sha256"]
    assert capture_classpath(args.evaluation_classpath)==frozen["evaluation_classpath_sha256"]
    assert digest(checkpoint/"model.safetensors")==parent["checkpoint_sha256"]
    assert digest(checkpoint/"config.json")==frozen["parent_checkpoint_config_sha256"]
    assert digest(parent_dir/"training-report.json")==frozen["parent_report_sha256"]
    assert digest(parent_dir/"freeze.json")==frozen["parent_freeze_sha256"]
    assert json.loads((args.base_dir/"manifest.json").read_text())==base
    assert all(digest(args.base_dir/"original"/n)==s for n,s in base["source_sha256"].items())
    assert digest(root/"data-snapshot.json")==frozen["training_data_sha256"]
    assert digest(root/"ancestor-data-snapshot.json")==frozen["ancestor_training_data_sha256"]
    assert digest(root/"runtime-snapshot.json")==frozen["runtime_manifest_sha256"]
    assert capture_runtime()==runtime
    write(root/"training-report.json",report);print(json.dumps(report,ensure_ascii=False),flush=True)


if __name__=="__main__":main()
