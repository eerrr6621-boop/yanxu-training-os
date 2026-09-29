"""Export a NEW experimental artifact; never masquerade as the base revision.
Intentionally incompatible with production's pinned-model allowlist.
"""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import shutil

os.environ.update(HF_HUB_OFFLINE="1",TRANSFORMERS_OFFLINE="1",TOKENIZERS_PARALLELISM="false")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--base-dir",type=Path,required=True)
    p.add_argument("--training-dir",type=Path,required=True)
    p.add_argument("--output-dir",type=Path,required=True)
    args=p.parse_args()
    root=args.output_dir.resolve()
    repo=Path(__file__).resolve().parent.parent
    if root.is_relative_to(repo) or "web" in root.parts:
        raise ValueError("Keep experimental artifacts private and outside the repository")
    training=json.loads((args.training_dir/"training-report.json").read_text())
    freeze=json.loads((args.training_dir/"freeze.json").read_text())
    assert training["completed"] and training["trained"] and training["synthetic_pilot_only"]
    checkpoint=args.training_dir/"checkpoint"
    assert sha(checkpoint/"model.safetensors")==training["checkpoint_sha256"]
    root.mkdir(parents=True,mode=0o700,exist_ok=False)
    import torch
    import onnx
    from transformers import AutoModel
    from onnxruntime.quantization import quantize_dynamic,QuantType
    torch.set_num_threads(1)
    model=AutoModel.from_pretrained(checkpoint,local_files_only=True,use_safetensors=True,
        trust_remote_code=False,attn_implementation="eager").float().eval()
    assert sum(p.numel() for p in model.parameters())==training["parameter_count"]
    class Embedding(torch.nn.Module):
        def __init__(self,encoder):
            super().__init__();self.encoder=encoder
        def forward(self,input_ids,attention_mask,token_type_ids):
            states=self.encoder(input_ids=input_ids,attention_mask=attention_mask,token_type_ids=token_type_ids).last_hidden_state
            return torch.nn.functional.normalize(states[:,0],p=2,dim=1)
    dummy=(torch.ones((1,16),dtype=torch.long),torch.ones((1,16),dtype=torch.long),torch.zeros((1,16),dtype=torch.long))
    torch.onnx.export(Embedding(model),dummy,str(root/"model-fp32.onnx"),opset_version=17,dynamo=False,
        input_names=["input_ids","attention_mask","token_type_ids"],output_names=["embeddings"],
        dynamic_axes={"input_ids":{0:"batch",1:"sequence"},"attention_mask":{0:"batch",1:"sequence"},
                      "token_type_ids":{0:"batch",1:"sequence"},"embeddings":{0:"batch"}})
    onnx.checker.check_model(str(root/"model-fp32.onnx"))
    quantize_dynamic(str(root/"model-fp32.onnx"),str(root/"model-int8.onnx"),per_channel=True,
        weight_type=QuantType.QInt8,op_types_to_quantize=["MatMul","Gemm"])
    onnx.checker.check_model(str(root/"model-int8.onnx"))
    base_tokenizer_path=args.base_dir.resolve()/"tokenizer.json"
    assert sha(base_tokenizer_path)==freeze["base_manifest"]["sha256"]["tokenizer.json"]
    trained_tokenizer=json.loads((checkpoint/"tokenizer.json").read_text())
    base_tokenizer=json.loads(base_tokenizer_path.read_text())
    for field in ("model","normalizer","pre_tokenizer","post_processor","added_tokens"):
        assert trained_tokenizer.get(field)==base_tokenizer.get(field), "Tokenizer changed during weights-only training"
    shutil.copyfile(base_tokenizer_path,root/"tokenizer.json")
    shutil.copyfile(checkpoint/"BASE_MODEL_CARD.md",root/"BASE_MODEL_CARD.md")
    files=("model-fp32.onnx","model-int8.onnx","tokenizer.json")
    manifest=dict(schema_version="yanxu-experimental-embedding-v1",model="yanxu/bge-small-zh-synthetic-pilot",
        revision="pilot-"+training["checkpoint_sha256"][:16],base_model=freeze["base_manifest"]["model"],
        base_revision=freeze["base_manifest"]["revision"],dimensions=512,pooling="CLS+L2",max_tokens=512,
        license="MIT; see BASE_MODEL_CARD.md; synthetic pilot only",production_approved=False,
        parameter_count=training["parameter_count"],training_data_sha256=freeze["training_data_sha256"],
        training_config=training["config"],trained_weights_sha256=training["checkpoint_sha256"],
        tokenizer_base_sha256=freeze["base_manifest"]["sha256"]["tokenizer.json"],
        sha256={name:sha(root/name) for name in files},bytes={name:(root/name).stat().st_size for name in files},
        exported_at=datetime.now(timezone.utc).isoformat())
    # Saved tokenizer may reserialize metadata; use the exact base tokenizer for
    # inference, verified by the pinned manifest, to isolate weights-only impact.
    assert manifest["sha256"]["tokenizer.json"]==manifest["tokenizer_base_sha256"]
    (root/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(manifest,ensure_ascii=False),flush=True)


if __name__=="__main__":
    main()
