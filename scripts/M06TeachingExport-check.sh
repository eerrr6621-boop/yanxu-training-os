#!/usr/bin/env bash
set -euo pipefail
m06_script_dir="$(cd "$(dirname "$0")" && pwd)"
m06_root="${M06_TEACHING_APP_ROOT:-$(cd "$m06_script_dir/.." && pwd)}"
m06_source_dir="${M06_TEACHING_SOURCE_DIR:-$m06_root/src/com/training}"
m06_java="${M06_TEACHING_JAVA:-java}"
m06_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m06-teaching-export.XXXXXX")"
# Only this fresh invocation's synthetic H2 and compiled classes, after its Java process exits.
trap 'rm -rf "$m06_tmp"' EXIT
mkdir -p "$m06_tmp/classes" "$m06_tmp/data"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
m06_cp="$m06_root/lib/h2.jar:$m06_root/lib/pdfbox-app-3.0.8.jar:$m06_root/lib/ip2region-3.3.7.jar"
m06_sources=()
for m06_source in "$m06_root"/src/com/training/*.java; do
  case "$m06_source" in */ManagementReportsIntegration.java|*/ManagementTeachingWorkbook.java) continue;; esac
  m06_sources+=("$m06_source")
done
"$m06_java" -jar "$m06_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m06_cp" -d "$m06_tmp/classes" \
  "${m06_sources[@]}" "$m06_source_dir/ManagementReportsIntegration.java" "$m06_source_dir/ManagementTeachingWorkbook.java" "$m06_script_dir/M06TeachingExportTest.java"
m06_args=("$m06_tmp/data")
if [[ -n "${M06_TEACHING_FIXTURE:-}" ]]; then m06_args+=("$M06_TEACHING_FIXTURE"); fi
"$m06_java" -cp "$m06_tmp/classes:$m06_cp" com.training.M06TeachingExportTest "${m06_args[@]}"
