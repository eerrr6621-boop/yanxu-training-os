"""Reuse checked weights-only ONNX export, preserving explicit v2 lineage."""
import json
import argparse
from pathlib import Path
from export_posttrain_pilot import main as export_main, sha

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--base-dir",type=Path,required=True)
    parser.add_argument("--training-dir",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,required=True)
    args=parser.parse_args();root=args.output_dir;training_dir=args.training_dir
    training=json.loads((training_dir/"training-report.json").read_text())
    frozen=json.loads((training_dir/"freeze.json").read_text())
    assert training["experiment"]=="balanced-augmentation-v2"
    assert training["parent_checkpoint_sha256"]==frozen["parent_checkpoint_sha256"]
    assert training["config"]==frozen["config"]
    assert sha(training_dir/"checkpoint/config.json")==training["checkpoint_config_sha256"]
    repo=Path(__file__).resolve().parent.parent
    assert all(sha(repo/"semantic"/n)==s for n,s in frozen["helper_sources_sha256"].items())
    # Validate lineage before creating any export; old exporter checks weight/tokenizer hashes.
    export_main()
    path=root/"manifest.json";m=json.loads(path.read_text())
    m.update(model="yanxu/bge-small-zh-augmented-v2",revision="v2-"+training["checkpoint_sha256"][:16],
             parent_checkpoint_sha256=training["parent_checkpoint_sha256"],production_approved=False,
             experiment="balanced-augmentation-v2")
    path.write_text(json.dumps(m,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(m,ensure_ascii=False),flush=True)
