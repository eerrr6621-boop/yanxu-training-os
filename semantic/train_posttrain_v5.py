"""Fixed final-step V4 continuation; prepare freezes inputs before any gradient."""
import argparse
from datetime import datetime, timezone
import math
from pathlib import Path
import random
import resource
import shutil
import sys
import time
import json
from posttrain_v5_artifacts import *

def rss():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024*1024 if sys.platform == "darwin" else 1024)

def load_encoder(parent, base):
    import torch
    from transformers import AutoModel, AutoTokenizer
    torch.set_num_threads(1); torch.set_num_interop_threads(1)
    torch.manual_seed(CONFIG["seed"]); random.seed(CONFIG["seed"])
    torch.use_deterministic_algorithms(True)
    model = AutoModel.from_pretrained(Path(parent)/"checkpoint", local_files_only=True,
        use_safetensors=True, trust_remote_code=False, attn_implementation="eager").float().eval()
    tokenizer = AutoTokenizer.from_pretrained(Path(base)/"original", local_files_only=True, trust_remote_code=False)
    for name, parameter in model.named_parameters():
        parameter.requires_grad_(name.startswith(CONFIG["trainable_prefix"]))
    return model, tokenizer

def position(lengths):
    p, n = lengths[0], lengths[1:]
    return "tied" if p in n else "shortest" if p < min(n) else "longest" if p > max(n) else "middle"

def length_inventory(rows, tokenizer):
    kinds = ("shortest", "middle", "longest", "tied")
    result = {g:dict.fromkeys(kinds, 0) for g in ("retained", "new")}
    cores = {g:dict.fromkeys(kinds, 0) for g in ("retained", "new")}; maximum = 0
    for row in rows:
        texts = [QUERY_PREFIX+row["query"]]+row["pos"]+row["neg"]
        lengths = [len(tokenizer(s, add_special_tokens=True)["input_ids"]) for s in texts]
        maximum = max(maximum, *lengths)
        require(max(lengths) <= CONFIG["max_tokens"], "refuse_fact_truncation")
        group = "new" if row["origin"] == "v5_new_synthetic_family" else "retained"
        result[group][position(lengths[1:])] += 1
        cores[group][position([len(tokenizer(s, add_special_tokens=True)["input_ids"]) for s in row["factual_passages"]])] += 1
    require(result["new"] == cores["new"] == dict(shortest=16, middle=16, longest=16, tied=0), "new_factual_length_balance")
    return dict(positive_token_length_positions=result, factual_core_token_length_positions=cores, maximum_tokens=maximum)

def prepare(args):
    from posttrain_v5_data import build_dataset, validate
    from posttrain_runtime_manifest import capture_runtime
    repo = Path(__file__).resolve().parent.parent; root = args.output_dir.resolve()
    require(not root.exists() and not root.is_relative_to(repo) and not any(x in root.parts for x in ("web", "public", "static")), "new_private_output_required")
    parent, base = args.parent_training_dir.resolve(), args.base_dir.resolve()
    report, manifest = verify_parent(parent, base)
    require(sha(args.seal_manifest) == SEAL_SHA, "opaque_seal_identity")
    pdata = read(parent/"data-snapshot.json"); data = build_dataset(pdata); inventory = validate(pdata)
    model, tokenizer = load_encoder(parent, base)
    lengths = length_inventory(data["train"], tokenizer)
    names = [n for n,p in model.named_parameters() if p.requires_grad]
    require(sum(p.numel() for p in model.parameters()) == report["parameter_count"] == 23953920, "parameter_count")
    require(sum(p.numel() for p in model.parameters() if p.requires_grad) == 3152384, "trainable_count")
    inputs = {str(parent/n):sha(parent/n) for n in PARENT_FILES}
    inputs.update({str(parent/"checkpoint"/n):sha(parent/"checkpoint"/n) for n in TOKENIZER_FILES})
    inputs.update({str(base/"original"/n):s for n,s in manifest["source_sha256"].items()})
    for path in (base/"manifest.json", base/"tokenizer.json", base/"original/README.md", parent/"checkpoint/BASE_MODEL_CARD.md"):
        inputs[str(path)] = sha(path)
    runtime = capture_runtime()
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    write(root/"data-snapshot.json", data); write(root/"runtime-snapshot.json", runtime)
    frozen = dict(experiment=EXPERIMENT, config=CONFIG, query_prefix=QUERY_PREFIX, expected_optimizer_updates=96,
        parent_training_directory=str(parent), base_directory=str(base), parent_model="yanxu/bge-small-zh-anchored-v4",
        parent_revision="v4-"+PARENT_FILES["checkpoint/model.safetensors"][:16], base_manifest=manifest,
        input_files_sha256=inputs, helper_sources_sha256={n:sha(repo/"semantic"/n) for n in HELPERS},
        trainable_parameter_names=names, training_data_inventory=inventory, lengths=lengths,
        training_data_sha256=sha(root/"data-snapshot.json"), runtime_manifest_sha256=sha(root/"runtime-snapshot.json"),
        opaque_seal_path=str(args.seal_manifest.resolve()), opaque_seal_sha256=SEAL_SHA,
        seal_content_read=False, holdout_used_for_training_or_selection=False, real_resumes_used=False,
        base_model_card_sha256=sha(base/"original/README.md"), production_sources_in_training_freeze=False,
        evaluation_provenance="Separate later pipeline freeze; not the old V4 source freeze.",
        frozen_at=datetime.now(timezone.utc).isoformat())
    write(root/"freeze.json", frozen)
    print(json.dumps(dict(prepared=True, trained=False, freeze_sha256=sha(root/"freeze.json"),
        training_data_sha256=frozen["training_data_sha256"], inventory=inventory, lengths=lengths), ensure_ascii=False), flush=True)

def train(root):
    import torch
    root = Path(root); require(not (root/"training-report.json").exists() and not (root/"checkpoint").exists(), "refuse_rerun_or_overwrite")
    f = verify_inputs(root); data = read(root/"data-snapshot.json")
    model, tokenizer = load_encoder(f["parent_training_directory"], f["base_directory"])
    require(length_inventory(data["train"], tokenizer) == f["lengths"], "length_inventory_changed")
    names = [n for n,p in model.named_parameters() if p.requires_grad]
    require(names == f["trainable_parameter_names"], "trainable_inventory_changed")
    fixed = {n:p.detach().clone() for n,p in model.named_parameters() if not p.requires_grad}
    watched = {n:p.detach().clone() for n,p in model.named_parameters() if p.requires_grad}
    parameters = [p for p in model.parameters() if p.requires_grad]
    started = time.monotonic(); batches=[]
    for row in data["train"]:
        tokens=tokenizer([QUERY_PREFIX+row["query"]]+row["pos"]+row["neg"], padding=True, truncation=False, return_tensors="pt")
        with torch.no_grad():
            anchor=torch.nn.functional.normalize(model(**tokens).last_hidden_state[:,0],p=2,dim=1).detach()
        batches.append((tokens,anchor))
    optimizer=torch.optim.AdamW(parameters,lr=CONFIG["learning_rate"],weight_decay=CONFIG["weight_decay"],foreach=False)
    optimizer.zero_grad(set_to_none=True); norms=[]; losses=[];updates=0
    for epoch in range(CONFIG["epochs"]):
        order=list(range(len(batches))); random.Random(CONFIG["seed"]+epoch).shuffle(order)
        values=[]; retrieval_values=[]; retention_values=[]
        for step,index in enumerate(order):
            require(time.monotonic()-started <= CONFIG["max_train_seconds"], "training_timeout")
            tokens,anchor=batches[index]
            emb=torch.nn.functional.normalize(model(**tokens).last_hidden_state[:,0],p=2,dim=1)
            scores=(emb[1:]@emb[0])/CONFIG["temperature"]
            retrieval=torch.logsumexp(scores,dim=0)-scores[0]
            retention=(1-(emb*anchor).sum(dim=1)).clamp_min(0).mean()
            loss=retrieval+CONFIG["retention_weight"]*retention
            require(bool(torch.isfinite(loss)), "nonfinite_loss")
            (loss/CONFIG["accumulation"]).backward()
            values.append(float(loss.detach()));retrieval_values.append(float(retrieval.detach()));retention_values.append(float(retention.detach()))
            if (step+1)%CONFIG["accumulation"]==0:
                norm=torch.nn.utils.clip_grad_norm_(parameters,CONFIG["max_grad_norm"])
                require(bool(torch.isfinite(norm)) and float(norm)>0,"gradient_invalid")
                norms.append(float(norm));optimizer.step();optimizer.zero_grad(set_to_none=True);updates+=1
            if (step+1)%48==0:
                print(json.dumps(dict(epoch=epoch+1,examples=step+1,optimizer_updates=updates,loss=sum(values)/len(values),seconds=round(time.monotonic()-started,2))),flush=True)
        losses.append(dict(epoch=epoch+1,mean_loss=sum(values)/len(values),retrieval_loss=sum(retrieval_values)/len(values),retention_loss=sum(retention_values)/len(values)))
    require(updates==len(norms)==96,"fixed_update_count")
    current=dict(model.named_parameters())
    require(all(torch.equal(current[n].detach(),p) for n,p in fixed.items()),"lower_layer_changed")
    changes={n:float((current[n].detach()-p).abs().mean()) for n,p in watched.items()}
    require(all(math.isfinite(x) for x in changes.values()) and any(x>0 for x in changes.values()),"no_finite_weight_change")
    verify_inputs(root)
    checkpoint=root/"checkpoint";model.save_pretrained(checkpoint,safe_serialization=True);tokenizer.save_pretrained(checkpoint)
    shutil.copyfile(Path(f["base_directory"])/"original/README.md",checkpoint/"BASE_MODEL_CARD.md")
    report=dict(completed=True,trained=True,deployed=False,experiment=EXPERIMENT,config=CONFIG,
        started_at=f["frozen_at"],finished_at=datetime.now(timezone.utc).isoformat(),freeze_sha256=sha(root/"freeze.json"),
        parameter_count=sum(p.numel() for p in model.parameters()),trainable_parameter_count=sum(p.numel() for p in parameters),
        trainable_parameter_names=names,architecture_added_parameters=0,frozen_parameters_unchanged=True,
        examples=192,independent_training_families=48,dev_examples=0,epochs=2,optimizer_updates=updates,
        nonzero_gradient_updates=len(norms),minimum_preclip_gradient_norm=min(norms),maximum_preclip_gradient_norm=max(norms),
        training_seconds=round(time.monotonic()-started,3),peak_training_process_rss_mib=round(rss(),2),losses=losses,
        training_max_observed_tokens=f["lengths"]["maximum_tokens"],length_inventory=f["lengths"],
        length_bias_note="New 12 fact families have 4 shortest / 4 middle / 4 longest positives. V4 retained rows and their existing biases remain unchanged. This does not prove absence of lexical shortcuts.",
        checkpoint_sha256=sha(checkpoint/"model.safetensors"),checkpoint_config_sha256=sha(checkpoint/"config.json"),
        checkpoint_tokenizer_sha256={n:sha(checkpoint/n) for n in TOKENIZER_FILES},
        parent_checkpoint_sha256=PARENT_FILES["checkpoint/model.safetensors"],
        parent_checkpoint_config_sha256=PARENT_FILES["checkpoint/config.json"],
        base_weights_sha256=f["base_manifest"]["source_sha256"]["model.safetensors"],
        watched_parameter_mean_absolute_change=changes,holdout_used_for_training_or_selection=False,
        real_resumes_used=False,production_approved=False)
    write(root/"training-report.json",report)
    verify_training(root,runtime=False)
    print(json.dumps(report,ensure_ascii=False),flush=True)

def main():
    require(not sys.flags.optimize and sys.dont_write_bytecode,"python_-B_without_-O_required")
    p=argparse.ArgumentParser();p.add_argument("phase",choices=("prepare","train"));p.add_argument("--output-dir",type=Path,required=True)
    p.add_argument("--base-dir",type=Path);p.add_argument("--parent-training-dir",type=Path);p.add_argument("--seal-manifest",type=Path)
    args=p.parse_args()
    if args.phase=="prepare":
        require(all((args.base_dir,args.parent_training_dir,args.seal_manifest)),"prepare_inputs_required");prepare(args)
    else:train(args.output_dir)

if __name__=="__main__":main()
