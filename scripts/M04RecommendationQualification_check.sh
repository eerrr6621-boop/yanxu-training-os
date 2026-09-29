#!/usr/bin/env bash
set -euo pipefail
m04_stage="$(cd "$(dirname "$0")/.." && pwd)"
m04_shared="${M04_SHARED_APP:-$m04_stage}"
m04_java="${M04_INTEGRATION_JAVA:-java}"
m04_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m04-recommendation-qualification.XXXXXX")"
# This run owns this directory; it contains only fresh synthetic data and compiled classes.
trap 'rm -rf "$m04_tmp"' EXIT
mkdir -p "$m04_tmp/out" "$m04_tmp/data"
m04_sources=()
for m04_source in "$m04_shared"/src/com/training/*.java; do
  case "${m04_source##*/}" in RecommendationQualification.java) continue ;; esac
  m04_sources+=("$m04_source")
done
"$m04_java" -jar "$m04_shared/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m04_shared/lib/h2.jar:$m04_shared/lib/pdfbox-app-3.0.8.jar:$m04_shared/lib/ip2region-3.3.7.jar" \
  -d "$m04_tmp/out" "${m04_sources[@]}" \
  "$m04_stage/src/com/training/RecommendationQualification.java" \
  "$m04_stage/scripts/M04RecommendationQualificationTest.java"
"$m04_java" -cp "$m04_tmp/out:$m04_shared/lib/h2.jar" \
  com.training.M04RecommendationQualificationTest "$m04_tmp/data"
