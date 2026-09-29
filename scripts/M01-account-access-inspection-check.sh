#!/usr/bin/env bash
set -euo pipefail
inspection_stage="$(cd "$(dirname "$0")/.." && pwd)"
inspection_app="${M01_INSPECTION_APP_ROOT:-$inspection_stage}"
inspection_java="${M01_INSPECTION_JAVA:-java}"
inspection_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-access-inspection.XXXXXX")"
trap 'rm -rf "$inspection_tmp"' EXIT
mkdir -p "$inspection_tmp/out"
inspection_sources=()
for inspection_file in "$inspection_app"/src/com/training/*.java; do
 if [[ ! -f "$inspection_stage/src/com/training/$(basename "$inspection_file")" ]]; then inspection_sources+=("$inspection_file"); fi
done
for inspection_file in "$inspection_stage"/src/com/training/*.java; do inspection_sources+=("$inspection_file"); done
"$inspection_java" -jar "$inspection_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
 -cp "$inspection_app/lib/h2.jar:$inspection_app/lib/pdfbox-app-3.0.8.jar:$inspection_app/lib/ip2region-3.3.7.jar" \
 -d "$inspection_tmp/out" "${inspection_sources[@]}" "$inspection_stage/scripts/M01AccountAccessInspectionTest.java"
"$inspection_java" -cp "$inspection_tmp/out:$inspection_app/lib/h2.jar" com.training.M01AccountAccessInspectionTest "$inspection_tmp"
