"""V5 training-only provenance. Never opens sealed questions or old evaluators."""
import hashlib
import json
import os
from pathlib import Path

os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                  TOKENIZERS_PARALLELISM="false", WANDB_DISABLED="true", PYTHONDONTWRITEBYTECODE="1")
EXPERIMENT = "anchored-top-layer-v5"
MODEL = "yanxu/bge-small-zh-anchored-v5"
BASE_MODEL = "BAAI/bge-small-zh-v1.5"
BASE_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
CONFIG = dict(seed=20260911, epochs=2, learning_rate=3e-6, weight_decay=0.01,
    temperature=0.05, accumulation=4, max_tokens=256, max_grad_norm=1.0,
    retention_weight=10.0, trainable_prefix="encoder.layer.3.", dropout=False,
    device="cpu", threads=1, max_train_seconds=900,
    checkpoint_selection="fixed final step; no dev/test checkpoint selection")
PARENT_FILES = {
    "checkpoint/model.safetensors": "3059a19e20ea29b94394f855f0b11ed9d0441e570515cc36658ba3e4653dfeb1",
    "checkpoint/config.json": "def0e46de71328d89acbbbc4e55c8274982518153f1e69ef0e3710a3fab421d7",
    "training-report.json": "21445eaba68d96b1ff4d848a077495e53d72785469021b99498675f300f46abd",
    "freeze.json": "4d49eafcc710c871abd8115010727f5fbd2461ebd51c4daf5311c18ae20f33e5",
    "data-snapshot.json": "3246df663da653ff932444556ac5a4d182be7f76035c2eb83ddad9bd581da24c",
}
SEAL_SHA = "bfed7f4bf90affb3b8fa0323ac1639b3434ea5d834e61a3d4e8a450e389a5909"
TOKENIZER_FILES = ("tokenizer.json", "tokenizer_config.json", "special_tokens_map.json")
HELPERS = ("posttrain_v5_data.py", "train_posttrain_v5.py", "posttrain_v5_artifacts.py",
           "export_posttrain_v5.py", "test_posttrain_v5_data.py", "posttrain_runtime_manifest.py")

def require(condition, reason):
    if not condition:
        raise ValueError("v5:" + reason)

def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for part in iter(lambda: handle.read(1024*1024), b""):
            h.update(part)
    return h.hexdigest()

def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))

def write(path, value):
    with Path(path).open("x", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

def verify_parent(parent, base):
    parent, base = Path(parent), Path(base)
    require(all(sha(parent / n) == s for n, s in PARENT_FILES.items()), "fixed_V4_parent_changed")
    report, freeze = read(parent / "training-report.json"), read(parent / "freeze.json")
    require(report["experiment"] == "anchored-top-layer-v4" and report["completed"]
            and report["trained"] and report["deployed"] is False and report["examples"] == 144,
            "parent_not_completed_V4")
    manifest = read(base / "manifest.json")
    require(manifest["model"] == BASE_MODEL and manifest["revision"] == BASE_REVISION, "base_identity")
    require(freeze["base_manifest"] == manifest, "parent_base_manifest")
    require(all(sha(base / "original" / n) == s for n, s in manifest["source_sha256"].items()), "base_source_changed")
    require(sha(base / "tokenizer.json") == manifest["sha256"]["tokenizer.json"], "base_tokenizer_changed")
    require(report["checkpoint_sha256"] == PARENT_FILES["checkpoint/model.safetensors"], "parent_report_weights")
    require(all(sha(parent / "checkpoint" / n) == s for n, s in report["checkpoint_tokenizer_sha256"].items()), "parent_tokenizers")
    require(sha(parent / "checkpoint/BASE_MODEL_CARD.md") == sha(base / "original/README.md"), "parent_model_card")
    ignored = {"_name_or_path", "transformers_version", "torch_dtype", "dtype"}
    pc, bc = read(parent / "checkpoint/config.json"), read(base / "original/config.json")
    require({k:v for k,v in pc.items() if k not in ignored} == {k:v for k,v in bc.items() if k not in ignored}, "architecture_changed")
    return report, manifest

def verify_tokenizer_semantics(checkpoint, base_tokenizer):
    actual, expected = read(Path(checkpoint)/"tokenizer.json"), read(base_tokenizer)
    for key in ("model", "normalizer", "pre_tokenizer", "post_processor", "decoder", "added_tokens"):
        require(actual.get(key) == expected.get(key), "tokenizer_semantics:"+key)

def verify_inputs(root, *, runtime=True):
    root = Path(root); frozen = read(root / "freeze.json")
    require(frozen["experiment"] == EXPERIMENT and frozen["config"] == CONFIG, "config_or_experiment")
    require(frozen["expected_optimizer_updates"] == 96 and frozen["query_prefix"] == QUERY_PREFIX, "steps_or_query_prefix")
    repo = Path(__file__).resolve().parent
    require(set(frozen["helper_sources_sha256"]) == set(HELPERS), "helper_inventory")
    require(all(sha(repo/n) == s for n,s in frozen["helper_sources_sha256"].items()), "training_source_changed")
    require(all(sha(Path(n)) == s for n,s in frozen["input_files_sha256"].items()), "training_inputs_changed")
    require(sha(root/"data-snapshot.json") == frozen["training_data_sha256"], "data_changed")
    require(sha(root/"runtime-snapshot.json") == frozen["runtime_manifest_sha256"], "runtime_manifest_changed")
    require(sha(frozen["opaque_seal_path"]) == frozen["opaque_seal_sha256"] == SEAL_SHA, "seal_changed")
    require(len(frozen["trainable_parameter_names"]) > 0 and all(n.startswith(CONFIG["trainable_prefix"]) for n in frozen["trainable_parameter_names"]), "trainable_inventory")
    require(frozen["training_data_inventory"]["retained_verbatim"] == 144 and frozen["training_data_inventory"]["new_phrasings"] == 48, "data_inventory")
    verify_parent(frozen["parent_training_directory"], frozen["base_directory"])
    if runtime:
        from posttrain_runtime_manifest import capture_runtime
        require(capture_runtime() == read(root/"runtime-snapshot.json"), "installed_runtime_changed")
    return frozen

def verify_training(root, *, runtime=True):
    root = Path(root); f = verify_inputs(root, runtime=runtime); r = read(root/"training-report.json")
    require(r["experiment"] == EXPERIMENT and r["config"] == CONFIG and r["completed"] and r["trained"] and r["deployed"] is False, "incomplete_training")
    require(r["optimizer_updates"] == r["nonzero_gradient_updates"] == 96, "updates")
    require(r["trainable_parameter_names"] == f["trainable_parameter_names"], "trainable_names_changed")
    require(r["frozen_parameters_unchanged"] is True and r["architecture_added_parameters"] == 0, "frozen_or_architecture")
    require(r["parameter_count"] == 23953920 and r["trainable_parameter_count"] == 3152384, "parameter_counts")
    require(r["examples"] == 192 and r["independent_training_families"] == 48 and r["dev_examples"] == 0, "training_counts")
    require(r["freeze_sha256"] == sha(root/"freeze.json"), "report_freeze")
    require(r["parent_checkpoint_sha256"] == PARENT_FILES["checkpoint/model.safetensors"], "report_parent")
    require(sha(root/"checkpoint/model.safetensors") == r["checkpoint_sha256"] != r["parent_checkpoint_sha256"], "checkpoint_weights")
    require(sha(root/"checkpoint/config.json") == r["checkpoint_config_sha256"], "checkpoint_config")
    require(set(r["checkpoint_tokenizer_sha256"]) == set(TOKENIZER_FILES), "tokenizer_inventory")
    require(all(sha(root/"checkpoint"/n) == s for n,s in r["checkpoint_tokenizer_sha256"].items()), "checkpoint_tokenizers")
    require(sha(root/"checkpoint/BASE_MODEL_CARD.md") == f["base_model_card_sha256"], "model_card_changed")
    verify_tokenizer_semantics(root/"checkpoint", Path(f["base_directory"])/"tokenizer.json")
    return r, f
