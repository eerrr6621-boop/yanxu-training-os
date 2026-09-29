#!/usr/bin/env bash
set -euo pipefail
m07_script_dir="$(cd "$(dirname "$0")" && pwd)"
m07_root="${M07_FORMAL_APP_ROOT:-$(cd "$m07_script_dir/.." && pwd)}"
m07_source="${M07_FORMAL_SOURCE_DIR:-$m07_root/src/com/training}"
m07_java="${M07_FORMAL_JAVA:-java}"
m07_regression="${M07_FORMAL_REGRESSION:-0}"
[[ "$m07_regression" == 0 || "$m07_regression" == 1 ]] || { echo "M07_FORMAL_REGRESSION must be 0 or 1" >&2; exit 2; }
m07_overrides=(SurveySummaryImportsFormal.java SurveySummaryImportsPolicy.java SurveySummaryImportsIntegration.java SurveySummaryImportsResponses.java)
for m07_name in "${m07_overrides[@]}"; do [[ -f "$m07_source/$m07_name" ]] || { echo "Missing candidate: $m07_name" >&2; exit 2; }; done
m07_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m07-formal.XXXXXX")"
# Only this run's synthetic H2 and compiled classes are removed after Java has exited.
trap 'rm -rf "$m07_tmp"' EXIT
mkdir -p "$m07_tmp/classes" "$m07_tmp/data"
m07_cp="$m07_root/lib/h2.jar:$m07_root/lib/pdfbox-app-3.0.8.jar:$m07_root/lib/ip2region-3.3.7.jar"
m07_sources=()
for m07_file in "$m07_root"/src/com/training/*.java; do
 case "${m07_file##*/}" in SurveySummaryImportsFormal.java|SurveySummaryImportsPolicy.java|SurveySummaryImportsIntegration.java|SurveySummaryImportsResponses.java)continue;; esac
 m07_sources+=("$m07_file")
done
for m07_name in "${m07_overrides[@]}"; do m07_sources+=("$m07_source/$m07_name"); done
# Regression mode runs the unchanged legacy suites instead of repeating the formal suite.
if [[ "$m07_regression" == 1 ]]; then
 m07_test_source="${M07_FORMAL_REGRESSION_TEST_DIR:-$m07_root/scripts}"
 for m07_test in M07ResponseImportsTest.java M07IntegrationTest.java; do
  [[ -f "$m07_test_source/$m07_test" ]] || { echo "Missing regression test: $m07_test" >&2; exit 2; }
 done
 m07_tests=("$m07_test_source/M07ResponseImportsTest.java" "$m07_test_source/M07IntegrationTest.java")
else
 m07_tests=("$m07_script_dir/M07FormalTest.java")
fi
"$m07_java" -jar "$m07_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m07_cp" -d "$m07_tmp/classes" "${m07_sources[@]}" "${m07_tests[@]}"
for m07_name in "${m07_overrides[@]}"; do shasum -a 256 "$m07_source/$m07_name"; done
if [[ "$m07_regression" == 1 ]]; then
 "$m07_java" -Xmx512m -cp "$m07_tmp/classes:$m07_cp" com.training.M07ResponseImportsTest
 "$m07_java" -Xmx512m -cp "$m07_tmp/classes:$m07_cp" com.training.M07IntegrationTest "$m07_tmp/data"
else
 "$m07_java" -Xmx512m -cp "$m07_tmp/classes:$m07_cp" com.training.M07FormalTest "$m07_tmp/data"
fi
