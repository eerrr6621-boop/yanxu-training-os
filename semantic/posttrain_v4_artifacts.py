"""Frozen V4 artifact verification; imports no questions or models at module load.

V3 is author-created synthetic testing, not a real-world blind benchmark.
The final checkpoint, source dependencies and executed classpath are verified
before any held-out generator is loaded.  Reused tests are diagnostic only.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import importlib.util
import json
import math
import os
from pathlib import Path
import random
import resource
import sys
import time
import unicodedata


os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                  HF_HUB_DISABLE_TELEMETRY="1", TOKENIZERS_PARALLELISM="false")
EXPERIMENT = "anchored-top-layer-v4"
V4_MODEL = "yanxu/bge-small-zh-anchored-v4"
TOKENIZER_FILES = {"tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"}
REQUIRED_HELPERS = {
    "posttrain_v4_artifacts.py", "export_posttrain_v4.py", "test_posttrain_v4_data.py",
    "evaluate_posttrain_v3.py", "export_posttrain_v3.py", "evaluate_posttrain_v2.py",
    "evaluate_posttrain_pilot.py", "export_posttrain_pilot.py", "train_posttrain_pilot.py",
    "posttrain_v2_holdout.py", "posttrain_holdout_cases.py", "test_posttrain_v3_pipeline_validation.py",
    "test_posttrain_v3_holdout_validation.py",
}


def require(condition, reason):
    if not condition:
        raise ValueError("v4_audit:" + reason)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def capture_classpath(classpath):
    """Return an ordered, relocatable digest of every classpath entry's bytes.

    Safe for the trainer to call before freezing: no Java execution, question
    import or model load.  Empty entries/wildcards/symlinked directory contents
    are refused so runtime class resolution cannot escape the digest inventory.
    """
    entries = classpath.split(os.pathsep)
    require(entries and all(entries), "empty_classpath_entry")
    result = []
    for entry in entries:
        require("*" not in entry, "classpath_wildcard")
        path = Path(entry).resolve()
        if path.is_dir():
            members = sorted(path.rglob("*"))
            require(not any(member.is_symlink() for member in members), "classpath_internal_symlink")
            files = {member.relative_to(path).as_posix(): sha(member)
                     for member in members if member.is_file()}
            require(bool(files), "empty_classpath_directory")
            result.append({"kind": "directory", "files": files})
        else:
            require(path.is_file(), "classpath_entry_missing")
            result.append({"kind": "file", "sha256": sha(path)})
    require(result[0]["kind"] == "directory" and
            "com/training/PosttrainingV2Probe.class" in result[0]["files"], "probe_not_in_first_classpath_entry")
    return result


def _safe_relative(root, name):
    path = (root / name).resolve()
    require(not Path(name).is_absolute() and path.is_relative_to(root.resolve()), "source_path_escape")
    return path


def verify_frozen_sources(repo, frozen):
    helpers = frozen["helper_sources_sha256"]
    require(REQUIRED_HELPERS <= set(helpers), "missing_helper_freeze")
    for name, expected in helpers.items():
        require(sha(_safe_relative(repo / "semantic", name)) == expected, "helper_changed:" + name)
    production = frozen["production_sources_sha256"]
    java_names = {path.relative_to(repo).as_posix() for path in (repo / "src/com/training").glob("*.java")}
    required = java_names | {"semantic/worker.py", "scripts/PosttrainingV2Probe.java"}
    require(required <= set(production), "missing_production_source_freeze")
    for name, expected in production.items():
        require(sha(_safe_relative(repo, name)) == expected, "production_source_changed:" + name)
    for name, field in (("train_posttrain_v4.py", "training_source_sha256"),
                        ("posttrain_v4_data.py", "data_generator_sha256")):
        require(sha(repo / "semantic" / name) == frozen[field], "generator_changed:" + name)
    require(sha(Path(frozen["sealed_holdout_path"])) == frozen["sealed_holdout_sha256"], "sealed_holdout_changed")


def verify_frozen_training(training_dir, repo):
    """Validate final lineage and opaque data hashes without loading questions."""
    trained = read_json(training_dir / "training-report.json")
    frozen = read_json(training_dir / "freeze.json")
    require(trained.get("experiment") == EXPERIMENT and frozen.get("experiment") == EXPERIMENT,
            "wrong_experiment")
    require(trained.get("completed") is True and trained.get("trained") is True and
            trained.get("deployed") is False, "training_not_complete_or_deployed")
    config = trained["config"]
    require(config == frozen["config"], "training_config_changed")
    require(config.get("epochs") == 3 and config.get("learning_rate") == 3e-6,
            "not_prespecified_v4_schedule")
    examples, accumulation = trained["examples"], config["accumulation"]
    require(type(examples) is int and examples > 0 and type(accumulation) is int and
            accumulation > 0 and examples % accumulation == 0, "invalid_update_denominator")
    expected_updates = config["epochs"] * examples // accumulation
    require(trained["optimizer_updates"] == frozen["expected_optimizer_updates"] == expected_updates,
            "not_fixed_final_step")
    require(config.get("trainable_prefix") == "encoder.layer.3." and
            frozen.get("trainable_parameter_prefix") == trained.get("trainable_parameter_prefix") ==
            "encoder.layer.3.", "not_top_layer_only")
    if "trainable_parameter_names" in frozen:
        names = frozen["trainable_parameter_names"]
        require(isinstance(names, list) and bool(names) and len(set(names)) == len(names) and
                all(isinstance(name, str) and name.startswith("encoder.layer.3.") for name in names),
                "invalid_trainable_parameter_inventory")
        require(trained.get("trainable_parameter_names") == names, "trainable_parameters_changed")
    for field in ("parent_checkpoint_sha256", "parent_checkpoint_config_sha256"):
        require(trained[field] == frozen[field], "parent_lineage_changed:" + field)
    checkpoint = training_dir / "checkpoint"
    require(sha(checkpoint / "model.safetensors") == trained["checkpoint_sha256"], "checkpoint_weights_changed")
    require(sha(checkpoint / "config.json") == trained["checkpoint_config_sha256"], "checkpoint_config_changed")
    tokenizer_hashes = trained["checkpoint_tokenizer_sha256"]
    require(isinstance(tokenizer_hashes, dict) and set(tokenizer_hashes) == TOKENIZER_FILES,
            "tokenizer_inventory_incomplete")
    for name, expected in tokenizer_hashes.items():
        require(sha(checkpoint / name) == expected, "checkpoint_tokenizer_changed:" + name)
    require(sha(training_dir / "data-snapshot.json") == frozen["training_data_sha256"], "training_data_changed")
    require(sha(training_dir / "ancestor-data-snapshot.json") == frozen["ancestor_training_data_sha256"],
            "ancestor_data_changed")
    require(examples == 144 and trained["independent_training_families"] == 36, "unexpected_v4_inventory")
    require(trained["nonzero_gradient_updates"] == expected_updates and
            trained["frozen_parameters_unchanged"] is True and
            trained["architecture_added_parameters"] == 0, "training_effect_not_verified")
    require(sha(training_dir / "runtime-snapshot.json") == frozen["runtime_manifest_sha256"] ==
            trained["runtime_manifest_sha256"], "runtime_snapshot_changed")
    parent = Path(frozen["parent_training_directory"])
    require(sha(parent / "training-report.json") == frozen["parent_report_sha256"] and
            sha(parent / "freeze.json") == frozen["parent_freeze_sha256"], "parent_metadata_changed")
    require(sha(parent / "checkpoint/model.safetensors") == frozen["parent_checkpoint_sha256"] and
            sha(parent / "checkpoint/config.json") == frozen["parent_checkpoint_config_sha256"],
            "parent_artifact_changed")
    verify_frozen_sources(repo, frozen)
    return trained, frozen


def verify_tokenizer_semantics(checkpoint, base_tokenizer_path):
    trained = read_json(checkpoint / "tokenizer.json")
    base = read_json(base_tokenizer_path)
    for field in ("model", "normalizer", "pre_tokenizer", "post_processor", "added_tokens"):
        require(trained.get(field) == base.get(field), "tokenizer_semantics_changed:" + field)


