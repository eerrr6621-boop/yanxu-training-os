"""Deterministic local export/quantization smoke comparison, no external data.
Run once per precision in separate processes so peak RSS is meaningful.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import resource
import sys
import time
from worker import Encoder

parser = argparse.ArgumentParser()
parser.add_argument("--model-dir", required=True)
parser.add_argument("--precision", choices=["fp32", "int8"], required=True)
parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("evaluation_cases.json"))
parser.add_argument("--output", type=Path, help="Save a generated report; input is synthetic cases only")
args = parser.parse_args()
started = time.monotonic()
encoder = Encoder(args.model_dir, precision=args.precision)
startup = time.monotonic() - started
cases = json.loads(args.cases.read_text(encoding="utf-8"))["cases"]
results = []
for case in cases:
    documents = [{"id": index + 1, "text": text} for index, text in enumerate(case["documents"])]
    result = encoder.compare({"query": case["query"], "documents": documents})
    ranking = sorted(result["results"], key=lambda row: -(row["similarity"] if row["similarity"] is not None else -2))
    warm_started = time.monotonic()
    warm = encoder.compare({"query": case["query"], "documents": documents})
    assert warm["results"] == result["results"], "Cache changed output"
    results.append({"query": case["query"], "top1": ranking[0]["id"],
                    "scores": [row["similarity"] for row in result["results"]],
                    "cold_ms": result["elapsed_ms"], "warm_ms": round((time.monotonic() - warm_started) * 1000, 2)})
rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
report = {"notice": "Synthetic smoke cases, NOT production accuracy or target-server performance",
                  "generated_at": datetime.now(timezone.utc).isoformat(),
                  "platform": platform.system() + " " + platform.machine(),
                  "cases_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                  "model_sha256": encoder.version,
                  "precision": args.precision, "cases": len(cases),
                  "positive_top1": sum(row["top1"] == 1 for row in results),
                  "startup_seconds": round(startup, 3),
                  "peak_rss_mib": round(rss / (1024 * 1024 if sys.platform == "darwin" else 1024), 2),
                  "results": results}
rendered = json.dumps(report, ensure_ascii=False, indent=2)
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered + "\n", encoding="utf-8")
print(rendered)
