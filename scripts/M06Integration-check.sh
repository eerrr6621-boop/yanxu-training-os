#!/usr/bin/env bash
set -euo pipefail
m06_script_dir="$(cd "$(dirname "$0")" && pwd)"
m06_root="${M06_INTEGRATION_APP_ROOT:-$(cd "$m06_script_dir/.." && pwd)}"
m06_java="${M06_INTEGRATION_JAVA:-java}"
m06_adapter="${M06_INTEGRATION_ADAPTER_SOURCE:-}"
m06_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m06-integration.XXXXXX")"
# Only this invocation's fresh classes and synthetic H2 files are removed after Java exits.
trap 'rm -rf "$m06_tmp"' EXIT
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
mkdir -p "$m06_tmp/classes" "$m06_tmp/data"
m06_classpath="$m06_root/lib/h2.jar:$m06_root/lib/pdfbox-app-3.0.8.jar:$m06_root/lib/ip2region-3.3.7.jar"
m06_sources=()
for m06_source in "$m06_root"/src/com/training/*.java; do
  if [[ -n "$m06_adapter" && "$m06_source" == */ManagementReportsIntegration.java ]]; then continue; fi
  m06_sources+=("$m06_source")
done
if [[ -n "$m06_adapter" ]]; then m06_sources+=("$m06_adapter"); fi
"$m06_java" -jar "$m06_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m06_classpath" -d "$m06_tmp/classes" \
  "${m06_sources[@]}" "$m06_script_dir/M06IntegrationTest.java"
"$m06_java" -cp "$m06_tmp/classes:$m06_classpath" com.training.M06IntegrationTest "$m06_tmp/data"
