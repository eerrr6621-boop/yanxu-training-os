#!/usr/bin/env bash
set -euo pipefail
m07_script_dir="$(cd "$(dirname "$0")" && pwd)"
m07_root="${M07_INTEGRATION_APP_ROOT:-$(cd "$m07_script_dir/.." && pwd)}"
m07_java="${M07_INTEGRATION_JAVA:-java}"
m07_source="${M07_INTEGRATION_SOURCE_DIR:-$m07_root/src/com/training}"
for m07_name in SurveySummaryImportsIntegration.java SurveySummaryImportsResponses.java; do
  [[ -f "$m07_source/$m07_name" ]] || { echo "Missing M07 source: $m07_name" >&2; exit 2; }
done
m07_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m07-integration.XXXXXX")"
# Remove only this invocation's synthetic H2 and compiled classes, after Java exits.
trap 'rm -rf "$m07_tmp"' EXIT
mkdir -p "$m07_tmp/classes" "$m07_tmp/data"
m07_classpath="$m07_root/lib/h2.jar:$m07_root/lib/pdfbox-app-3.0.8.jar:$m07_root/lib/ip2region-3.3.7.jar"
m07_sources=()
for m07_file in "$m07_root"/src/com/training/*.java; do
  case "${m07_file##*/}" in SurveySummaryImportsIntegration.java|SurveySummaryImportsResponses.java) continue ;; esac
  m07_sources+=("$m07_file")
done
m07_sources+=("$m07_source/SurveySummaryImportsIntegration.java" "$m07_source/SurveySummaryImportsResponses.java")
"$m07_java" -jar "$m07_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m07_classpath" -d "$m07_tmp/classes" \
  "${m07_sources[@]}" "$m07_script_dir/M07IntegrationTest.java"
"$m07_java" -Xmx512m -cp "$m07_tmp/classes:$m07_classpath" com.training.M07IntegrationTest "$m07_tmp/data"
