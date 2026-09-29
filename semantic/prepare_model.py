"""One-time LOCAL export of the pinned BAAI model. Never uploads any documents.

Use a dedicated build venv. Production needs requirements.txt only.
The output directory must be new and must not be inside web/.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request

MODEL = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
FILES = ("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json",
         "special_tokens_map.json", "vocab.txt", "README.md")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = args.output.resolve()
    if "web" in root.parts:
        raise ValueError("Model artifacts must not be publicly served")
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    original = root / "original"
    original.mkdir(mode=0o700)
    hashes = {}
    for name in FILES:
        with urllib.request.urlopen(f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}", timeout=90) as src:
            with (original / name).open("xb") as dst:
                shutil.copyfileobj(src, dst)
        hashes[name] = hashlib.sha256((original / name).read_bytes()).hexdigest()
    # Disable all model-hub traffic during export/inference; only safetensors, no pickle.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    import torch
    import onnx
    from transformers import AutoModel
    from onnxruntime.quantization import quantize_dynamic, QuantType

    torch.set_num_threads(1)
    model = AutoModel.from_pretrained(str(original), local_files_only=True,
                                     use_safetensors=True, trust_remote_code=False,
                                     attn_implementation="eager").eval()

    class Embedding(torch.nn.Module):
        def __init__(self, encoder):
            super().__init__()
            self.encoder = encoder

        def forward(self, input_ids, attention_mask, token_type_ids):
            states = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                                  token_type_ids=token_type_ids).last_hidden_state
            return torch.nn.functional.normalize(states[:, 0], p=2, dim=1)

    dummy = (torch.ones((1, 16), dtype=torch.long), torch.ones((1, 16), dtype=torch.long),
             torch.zeros((1, 16), dtype=torch.long))
    full = root / "model-fp32.onnx"
    torch.onnx.export(Embedding(model), dummy, str(full), opset_version=17, dynamo=False,
                      input_names=["input_ids", "attention_mask", "token_type_ids"],
                      output_names=["embeddings"],
                      dynamic_axes={"input_ids": {0: "batch", 1: "sequence"},
                                    "attention_mask": {0: "batch", 1: "sequence"},
                                    "token_type_ids": {0: "batch", 1: "sequence"},
                                    "embeddings": {0: "batch"}})
    onnx.checker.check_model(str(full))
    quantize_dynamic(str(full), str(root / "model-int8.onnx"), per_channel=True,
                     weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
    shutil.copyfile(original / "tokenizer.json", root / "tokenizer.json")
    manifest = {"model": MODEL, "revision": REVISION, "dimensions": 512,
                "pooling": "CLS+L2", "max_tokens": 512, "license": "MIT",
                "source_sha256": hashes,
                "sha256": {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                           for name in ("model-fp32.onnx", "model-int8.onnx", "tokenizer.json")}}
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"model": MODEL, "revision": REVISION,
                      "bytes": {name: (root / name).stat().st_size for name in manifest["sha256"]}}))


if __name__ == "__main__":
    main()
