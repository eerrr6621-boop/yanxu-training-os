"""Offline weights-only V3 ONNX export with honest identity from first manifest."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from evaluate_posttrain_v3 import (
    EXPERIMENT, V3_MODEL, read_json, require, sha,
    verify_frozen_training, verify_tokenizer_semantics,
)


def build_manifest(training, frozen, file_hashes, file_bytes):
    require(training.get("experiment") == EXPERIMENT, "export_wrong_experiment")
    require(set(file_hashes) == {"model-fp32.onnx", "model-int8.onnx", "tokenizer.json"}, "export_file_inventory")
    require(file_hashes["tokenizer.json"] == frozen["base_manifest"]["sha256"]["tokenizer.json"],
            "export_tokenizer_mismatch")
    return dict(
        schema_version="yanxu-experimental-embedding-v1", model=V3_MODEL,
        revision="v3-" + training["checkpoint_sha256"][:16], experiment=EXPERIMENT,
        base_model=frozen["base_manifest"]["model"], base_revision=frozen["base_manifest"]["revision"],
        dimensions=512, pooling="CLS+L2", max_tokens=512,
        license="MIT; see BASE_MODEL_CARD.md; synthetic experiment only", production_approved=False,
        parameter_count=training["parameter_count"], training_data_sha256=frozen["training_data_sha256"],
        training_config=training["config"], trained_weights_sha256=training["checkpoint_sha256"],
        checkpoint_config_sha256=training["checkpoint_config_sha256"],
        checkpoint_tokenizer_sha256=training["checkpoint_tokenizer_sha256"],
        parent_checkpoint_sha256=training["parent_checkpoint_sha256"],
        parent_checkpoint_config_sha256=training["parent_checkpoint_config_sha256"],
        tokenizer_base_sha256=frozen["base_manifest"]["sha256"]["tokenizer.json"],
        sha256=file_hashes, bytes=file_bytes, exported_at=datetime.now(timezone.utc).isoformat(),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-dir", type=Path, required=True)
    parser.add_argument("--training-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    repo, root = Path(__file__).resolve().parent.parent, args.output_dir.resolve()
    require(not root.is_relative_to(repo) and not root.exists() and
            not any(part in {"web", "public", "static"} for part in root.parts), "new_private_export_required")
    training, frozen = verify_frozen_training(args.training_dir, repo)
    frozen_bytes = sha(args.training_dir / "freeze.json")
    training_report_bytes = sha(args.training_dir / "training-report.json")
    checkpoint = args.training_dir / "checkpoint"
    base_tokenizer = args.base_dir.resolve() / "tokenizer.json"
    require(sha(base_tokenizer) == frozen["base_manifest"]["sha256"]["tokenizer.json"], "export_base_tokenizer_changed")
    require(read_json(args.base_dir / "manifest.json") == frozen["base_manifest"], "export_base_manifest_changed")
    verify_tokenizer_semantics(checkpoint, base_tokenizer)
    # All metadata, weights, config, source and tokenizer checks precede writes.
    root.mkdir(parents=True, mode=0o700, exist_ok=False)
    import torch
    import onnx
    from transformers import AutoModel
    from onnxruntime.quantization import quantize_dynamic, QuantType
    torch.set_num_threads(1)
    model = AutoModel.from_pretrained(checkpoint, local_files_only=True, use_safetensors=True,
                                     trust_remote_code=False, attn_implementation="eager").float().eval()
    require(sum(parameter.numel() for parameter in model.parameters()) == training["parameter_count"],
            "export_parameter_count_mismatch")

    class Embedding(torch.nn.Module):
        def __init__(self, encoder):
            super().__init__(); self.encoder = encoder

        def forward(self, input_ids, attention_mask, token_type_ids):
            states = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                                  token_type_ids=token_type_ids).last_hidden_state
            return torch.nn.functional.normalize(states[:, 0], p=2, dim=1)

    dummy = (torch.ones((1, 16), dtype=torch.long), torch.ones((1, 16), dtype=torch.long),
             torch.zeros((1, 16), dtype=torch.long))
    torch.onnx.export(Embedding(model), dummy, str(root / "model-fp32.onnx"), opset_version=17, dynamo=False,
                      input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["embeddings"],
                      dynamic_axes={"input_ids": {0: "batch", 1: "sequence"},
                                    "attention_mask": {0: "batch", 1: "sequence"},
                                    "token_type_ids": {0: "batch", 1: "sequence"}, "embeddings": {0: "batch"}})
    onnx.checker.check_model(str(root / "model-fp32.onnx"))
    quantize_dynamic(str(root / "model-fp32.onnx"), str(root / "model-int8.onnx"), per_channel=True,
                     weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
    onnx.checker.check_model(str(root / "model-int8.onnx"))
    shutil.copyfile(base_tokenizer, root / "tokenizer.json")
    shutil.copyfile(checkpoint / "BASE_MODEL_CARD.md", root / "BASE_MODEL_CARD.md")
    require(sha(args.training_dir / "freeze.json") == frozen_bytes and
            sha(args.training_dir / "training-report.json") == training_report_bytes,
            "training_metadata_changed_during_export")
    verify_frozen_training(args.training_dir, repo)
    files = ("model-fp32.onnx", "model-int8.onnx", "tokenizer.json")
    manifest = build_manifest(training, frozen, {name: sha(root / name) for name in files},
                              {name: (root / name).stat().st_size for name in files})
    manifest["freeze_sha256"] = frozen_bytes
    manifest["training_report_sha256"] = training_report_bytes
    with (root / "manifest.json").open("x", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps(manifest, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
