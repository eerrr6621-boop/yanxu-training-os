#!/usr/bin/env bash
set -euo pipefail
m08_script_dir="$(cd "$(dirname "$0")" && pwd)"
m08_root="${M08_INTEGRATION_APP_ROOT:-$(cd "$m08_script_dir/.." && pwd)}"
m08_java="${M08_INTEGRATION_JAVA:-java}"
m08_source="${M08_INTEGRATION_SOURCE_DIR:-$m08_root/src/com/training}"
[[ -f "$m08_source/TrainingSummariesIntegration.java" ]] || { echo "Missing M08 integration source" >&2; exit 2; }
[[ -f "$m08_root/lib/ecj.jar" && -f "$m08_root/lib/h2.jar" ]] || { echo "Missing app compiler or isolated H2 dependency" >&2; exit 2; }
m08_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m08-integration.XXXXXX")"
# Remove only this invocation's synthetic H2 and compiled classes after Java exits.
trap 'rm -rf -- "$m08_tmp"' EXIT
mkdir -p "$m08_tmp/classes" "$m08_tmp/data"
m08_classpath="$m08_root/lib/h2.jar:$m08_root/lib/pdfbox-app-3.0.8.jar:$m08_root/lib/ip2region-3.3.7.jar"
m08_sources=()
for m08_file in "$m08_root"/src/com/training/*.java; do
  case "${m08_file##*/}" in TrainingSummariesIntegration.java) continue ;; esac
  m08_sources+=("$m08_file")
done
m08_sources+=("$m08_source/TrainingSummariesIntegration.java")
"$m08_java" -jar "$m08_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m08_classpath" -d "$m08_tmp/classes" \
  "${m08_sources[@]}" "$m08_script_dir/M08IntegrationTest.java"
"$m08_java" -Xmx256m -Dfile.encoding=UTF-8 -cp "$m08_tmp/classes:$m08_classpath" \
  com.training.M08IntegrationTest "$m08_tmp/data"
