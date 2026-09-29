#!/usr/bin/env bash
set -euo pipefail
m05_script_dir="$(cd "$(dirname "$0")" && pwd)"
m05_root="${M05_INTEGRATION_APP_ROOT:-$(cd "$m05_script_dir/.." && pwd)}"
m05_java="${M05_INTEGRATION_JAVA:-java}"
m05_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m05-integration.XXXXXX")"
# Only this invocation's fresh temporary output and synthetic H2 files are removed, after Java exits.
trap 'rm -rf "$m05_tmp"' EXIT
mkdir -p "$m05_tmp/classes" "$m05_tmp/data"
m05_classpath="$m05_root/lib/h2.jar:$m05_root/lib/pdfbox-app-3.0.8.jar:$m05_root/lib/ip2region-3.3.7.jar"
m05_tests=("$m05_script_dir/M05IntegrationTest.java")
if [[ -f "$m05_script_dir/M05IntegrationFrozenTest.java" ]]; then
  m05_tests+=("$m05_script_dir/M05IntegrationFrozenTest.java")
fi
"$m05_java" -jar "$m05_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m05_classpath" -d "$m05_tmp/classes" \
  "$m05_root"/src/com/training/*.java "${m05_tests[@]}"
"$m05_java" -cp "$m05_tmp/classes:$m05_classpath" com.training.M05IntegrationTest "$m05_tmp/data"
if [[ -f "$m05_script_dir/M05IntegrationFrozenTest.java" ]]; then
  "$m05_java" -cp "$m05_tmp/classes:$m05_classpath" com.training.M05IntegrationFrozenTest
fi
