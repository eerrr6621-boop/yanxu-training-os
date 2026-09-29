"""Offline V5 export: preserve actual identity, never edit production allowlists."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import sys
import json
from posttrain_v5_artifacts import *

def build_manifest(training, frozen, hashes, sizes):
    require(training["experiment"]==EXPERIMENT and training["parent_checkpoint_sha256"]==PARENT_FILES["checkpoint/model.safetensors"],"export_lineage")
    require(set(hashes)==set(sizes)=={"model-fp32.onnx","model-int8.onnx","tokenizer.json"},"export_inventory")
    require(hashes["tokenizer.json"]==frozen["base_manifest"]["sha256"]["tokenizer.json"],"export_tokenizer_identity")
    return dict(schema_version="yanxu-experimental-embedding-v1",model=MODEL,
        revision="v5-"+training["checkpoint_sha256"][:16],experiment=EXPERIMENT,
        base_model=BASE_MODEL,base_revision=BASE_REVISION,dimensions=512,pooling="CLS+L2",max_tokens=512,
        production_approved=False,license="MIT; see BASE_MODEL_CARD.md; synthetic experiment only",
        parameter_count=training["parameter_count"],training_data_sha256=frozen["training_data_sha256"],
        training_config=training["config"],trained_weights_sha256=training["checkpoint_sha256"],
        checkpoint_config_sha256=training["checkpoint_config_sha256"],checkpoint_tokenizer_sha256=training["checkpoint_tokenizer_sha256"],
        parent_model=frozen["parent_model"],parent_revision=frozen["parent_revision"],
        parent_checkpoint_sha256=training["parent_checkpoint_sha256"],
        parent_checkpoint_config_sha256=training["parent_checkpoint_config_sha256"],
        parent_training_report_sha256=PARENT_FILES["training-report.json"],parent_freeze_sha256=PARENT_FILES["freeze.json"],
        tokenizer_base_sha256=frozen["base_manifest"]["sha256"]["tokenizer.json"],
        base_model_card_sha256=frozen["base_model_card_sha256"],sha256=hashes,bytes=sizes,
        exported_at=datetime.now(timezone.utc).isoformat())

def main():
    require(not sys.flags.optimize and sys.dont_write_bytecode,"python_-B_without_-O_required")
    p=argparse.ArgumentParser();p.add_argument("--training-dir",type=Path,required=True);p.add_argument("--output-dir",type=Path,required=True)
    args=p.parse_args();root=args.output_dir.resolve();repo=Path(__file__).resolve().parent.parent
    require(not root.exists() and not root.is_relative_to(repo) and not any(x in root.parts for x in ("web","public","static")),"new_private_export_required")
    report,frozen=verify_training(args.training_dir)
    before=(sha(args.training_dir/"freeze.json"),sha(args.training_dir/"training-report.json"))
    checkpoint=args.training_dir/"checkpoint";base=Path(frozen["base_directory"])
    import torch
    import numpy as np
    import onnx
    import onnxruntime as ort
    from transformers import AutoModel,AutoTokenizer
    from onnxruntime.quantization import quantize_dynamic,QuantType
    torch.set_num_threads(1);torch.set_num_interop_threads(1)
    model=AutoModel.from_pretrained(checkpoint,local_files_only=True,use_safetensors=True,
        trust_remote_code=False,attn_implementation="eager").float().eval()
    require(sum(p.numel() for p in model.parameters())==report["parameter_count"],"export_architecture")
    class Embedding(torch.nn.Module):
        def __init__(self,encoder):super().__init__();self.encoder=encoder
        def forward(self,input_ids,attention_mask,token_type_ids):
            states=self.encoder(input_ids=input_ids,attention_mask=attention_mask,token_type_ids=token_type_ids).last_hidden_state
            return torch.nn.functional.normalize(states[:,0],p=2,dim=1)
    root.mkdir(mode=0o700,parents=True,exist_ok=False)
    dummy=(torch.ones((1,16),dtype=torch.long),torch.ones((1,16),dtype=torch.long),torch.zeros((1,16),dtype=torch.long))
    torch.onnx.export(Embedding(model),dummy,str(root/"model-fp32.onnx"),opset_version=17,dynamo=False,
        input_names=["input_ids","attention_mask","token_type_ids"],output_names=["embeddings"],
        dynamic_axes={"input_ids":{0:"batch",1:"sequence"},"attention_mask":{0:"batch",1:"sequence"},
                      "token_type_ids":{0:"batch",1:"sequence"},"embeddings":{0:"batch"}})
    onnx.checker.check_model(str(root/"model-fp32.onnx"))
    quantize_dynamic(str(root/"model-fp32.onnx"),str(root/"model-int8.onnx"),per_channel=True,weight_type=QuantType.QInt8,op_types_to_quantize=["MatMul","Gemm"])
    onnx.checker.check_model(str(root/"model-int8.onnx"))
    shutil.copyfile(base/"tokenizer.json",root/"tokenizer.json");shutil.copyfile(checkpoint/"BASE_MODEL_CARD.md",root/"BASE_MODEL_CARD.md")
    # Numerical smoke on already-used training facts only, not accuracy selection.
    tokenizer=AutoTokenizer.from_pretrained(checkpoint,local_files_only=True,trust_remote_code=False)
    row=read(args.training_dir/"data-snapshot.json")["train"][144]
    texts=[QUERY_PREFIX+row["query"],row["pos"][0],row["neg"][0]]
    encoded=tokenizer(texts,padding=True,truncation=False,return_tensors="np")
    session_options=ort.SessionOptions();session_options.intra_op_num_threads=1;session_options.inter_op_num_threads=1
    smoke={};vectors={}
    for name in ("model-fp32.onnx","model-int8.onnx"):
        session=ort.InferenceSession(str(root/name),sess_options=session_options,providers=["CPUExecutionProvider"])
        feed={x.name:encoded[x.name].astype(np.int64) for x in session.get_inputs()}
        result=session.run(None,feed)[0];norm=np.linalg.norm(result,axis=1)
        require(result.shape==(3,512) and np.isfinite(result).all() and np.allclose(norm,1,atol=1e-5),"export_output_invalid")
        vectors[name]=result;smoke[name]=dict(shape=list(result.shape),finite=True,unit_normalized=True)
    with torch.no_grad():
        tensors={k:torch.from_numpy(v).long() for k,v in encoded.items()}
        native=torch.nn.functional.normalize(model(**tensors).last_hidden_state[:,0],p=2,dim=1).numpy()
    fp_error=float(np.max(np.abs(native-vectors["model-fp32.onnx"])))
    require(fp_error<1e-4,"fp32_export_deviation")
    smoke.update(torch_fp32_max_absolute_error=fp_error,
        int8_fp32_vector_cosine=[float(x) for x in (vectors["model-int8.onnx"]*vectors["model-fp32.onnx"]).sum(axis=1)],
        kind="3 existing training texts; numerical export smoke, not new accuracy evidence")
    require(before==(sha(args.training_dir/"freeze.json"),sha(args.training_dir/"training-report.json")),"training_metadata_changed")
    verify_training(args.training_dir)
    names=("model-fp32.onnx","model-int8.onnx","tokenizer.json")
    manifest=build_manifest(report,frozen,{n:sha(root/n) for n in names},{n:(root/n).stat().st_size for n in names})
    manifest.update(freeze_sha256=before[0],training_report_sha256=before[1])
    write(root/"smoke-report.json",smoke);write(root/"manifest.json",manifest)
    print(json.dumps(dict(manifest=manifest,smoke=smoke),ensure_ascii=False),flush=True)

if __name__=="__main__":main()
