#!/usr/bin/env bash
set -euo pipefail
coverage_stage="$(cd "$(dirname "$0")/.." && pwd)"
coverage_app="${M01_COVERAGE_APP_ROOT:-$coverage_stage}"
coverage_java="${M01_COVERAGE_JAVA:-java}"
coverage_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-branch-coverage.XXXXXX")"
trap 'rm -rf "$coverage_tmp"' EXIT
mkdir -p "$coverage_tmp/out"
coverage_sources=()
for coverage_file in "$coverage_app"/src/com/training/*.java; do
  if [[ ! -f "$coverage_stage/src/com/training/$(basename "$coverage_file")" ]]; then coverage_sources+=("$coverage_file"); fi
done
for coverage_file in "$coverage_stage"/src/com/training/*.java; do coverage_sources+=("$coverage_file"); done
"$coverage_java" -jar "$coverage_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$coverage_app/lib/h2.jar:$coverage_app/lib/pdfbox-app-3.0.8.jar:$coverage_app/lib/ip2region-3.3.7.jar" \
  -d "$coverage_tmp/out" "${coverage_sources[@]}" \
  "$coverage_app/scripts/OrganizationAccountImportSourceTest.java" \
  "$coverage_app/scripts/OrganizationAccountImportTest.java" \
  "$coverage_stage/scripts/M01BranchCoverageTest.java"
"$coverage_java" -cp "$coverage_tmp/out:$coverage_app/lib/h2.jar" com.training.M01BranchCoverageTest "$coverage_tmp"
"$coverage_java" -cp "$coverage_tmp/out:$coverage_app/lib/h2.jar" com.training.OrganizationAccountImportSourceTest
mkdir -p "$coverage_tmp/compat-data" "$coverage_tmp/compat-sources"
"$coverage_java" -cp "$coverage_tmp/out:$coverage_app/lib/h2.jar" \
  com.training.OrganizationAccountImportTest "$coverage_tmp/compat-data" "$coverage_tmp/compat-sources"
