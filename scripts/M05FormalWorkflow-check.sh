#!/usr/bin/env bash
set -euo pipefail
m05_formal_script_dir="$(cd "$(dirname "$0")" && pwd)"
m05_formal_root="${M05_FORMAL_APP_ROOT:-$(cd "$m05_formal_script_dir/.." && pwd)}"
m05_formal_java="${M05_FORMAL_JAVA:-java}"
m05_formal_override="${M05_FORMAL_SOURCE_DIR:-}"
m05_formal_support_source="${M05_FORMAL_SUPPORT_SOURCE:-}"
m05_formal_regression="${M05_FORMAL_REGRESSION:-0}"
[[ "$m05_formal_regression" == 0 || "$m05_formal_regression" == 1 ]] || { echo "M05_FORMAL_REGRESSION must be 0 or 1" >&2; exit 2; }
m05_formal_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m05-formal.XXXXXX")"
# Remove only this invocation's fresh classes and synthetic H2 database after Java exits.
trap 'rm -rf "$m05_formal_tmp"' EXIT
mkdir -p "$m05_formal_tmp/classes" "$m05_formal_tmp/data"
m05_formal_classpath="$m05_formal_root/lib/h2.jar:$m05_formal_root/lib/pdfbox-app-3.0.8.jar:$m05_formal_root/lib/ip2region-3.3.7.jar"
m05_formal_sources=()
for m05_formal_source in "$m05_formal_root"/src/com/training/*.java; do
  m05_formal_name="${m05_formal_source##*/}"
  if [[ -n "$m05_formal_override" && -f "$m05_formal_override/$m05_formal_name" ]]; then continue; fi
  if [[ -n "$m05_formal_support_source" && "$m05_formal_name" == "${m05_formal_support_source##*/}" ]]; then continue; fi
  m05_formal_sources+=("$m05_formal_source")
done
if [[ -n "$m05_formal_override" ]]; then
  for m05_formal_source in "$m05_formal_override"/*.java; do
    [[ -f "$m05_formal_source" ]] || continue
    [[ "${m05_formal_source##*/}" == *Test.java ]] && continue
    m05_formal_sources+=("$m05_formal_source")
  done
fi
if [[ -n "$m05_formal_support_source" ]]; then
  [[ -f "$m05_formal_support_source" ]] || { echo "Explicit support source does not exist" >&2; exit 2; }
  m05_formal_sources+=("$m05_formal_support_source")
fi
m05_formal_tests=("$m05_formal_script_dir/M05FormalWorkflowTest.java")
if [[ -f "$m05_formal_script_dir/M05ExecutionTest.java" ]]; then
  m05_formal_tests+=("$m05_formal_script_dir/M05ExecutionTest.java")
fi
if [[ -f "$m05_formal_script_dir/M05CasesTest.java" ]]; then
  m05_formal_tests+=("$m05_formal_script_dir/M05CasesTest.java")
  mkdir -p "$m05_formal_tmp/cases-data"
fi
if [[ "$m05_formal_regression" == 1 ]]; then
  m05_formal_tests+=("$m05_formal_root/scripts/M05IntegrationTest.java" "$m05_formal_root/scripts/M05IntegrationFrozenTest.java" "$m05_formal_root/scripts/M05DeliveryHistoryTest.java")
  mkdir -p "$m05_formal_tmp/integration-data" "$m05_formal_tmp/history-data"
fi
"$m05_formal_java" -jar "$m05_formal_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m05_formal_classpath" -d "$m05_formal_tmp/classes" \
  "${m05_formal_sources[@]}" "${m05_formal_tests[@]}"
if [[ -f "$m05_formal_script_dir/M05ExecutionTest.java" ]]; then
  "$m05_formal_java" -cp "$m05_formal_tmp/classes:$m05_formal_classpath" com.training.M05ExecutionTest
fi
"$m05_formal_java" -cp "$m05_formal_tmp/classes:$m05_formal_classpath" \
  com.training.M05FormalWorkflowTest "$m05_formal_tmp/data"
if [[ -f "$m05_formal_script_dir/M05CasesTest.java" ]]; then
  "$m05_formal_java" -cp "$m05_formal_tmp/classes:$m05_formal_classpath" com.training.M05CasesTest "$m05_formal_tmp/cases-data"
fi
if [[ "$m05_formal_regression" == 1 ]]; then
  "$m05_formal_java" -cp "$m05_formal_tmp/classes:$m05_formal_classpath" com.training.M05IntegrationTest "$m05_formal_tmp/integration-data"
  "$m05_formal_java" -cp "$m05_formal_tmp/classes:$m05_formal_classpath" com.training.M05IntegrationFrozenTest
  "$m05_formal_java" -Xmx512m -cp "$m05_formal_tmp/classes:$m05_formal_classpath" com.training.M05DeliveryHistoryTest "$m05_formal_tmp/history-data"
fi
