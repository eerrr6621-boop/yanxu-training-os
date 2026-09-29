#!/usr/bin/env bash
set -euo pipefail
import_stage="$(cd "$(dirname "$0")/.." && pwd)"
import_app="${M01_IMPORT_APP_ROOT:-$import_stage}"
import_java="${M01_IMPORT_JAVA:-java}"
import_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-import-check.XXXXXX")"
# Only this script's fresh test directory is removed, after the JVM has exited.
trap 'rm -rf "$import_tmp"' EXIT
mkdir -p "$import_tmp/out" "$import_tmp/data" "$import_tmp/sources"
import_sources=()
for import_file in "$import_app"/src/com/training/*.java; do
  if [[ ! -f "$import_stage/src/com/training/$(basename "$import_file")" ]]; then
    import_sources+=("$import_file")
  fi
done
for import_file in "$import_stage"/src/com/training/*.java; do import_sources+=("$import_file"); done
"$import_java" -jar "$import_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$import_app/lib/h2.jar:$import_app/lib/pdfbox-app-3.0.8.jar:$import_app/lib/ip2region-3.3.7.jar" \
  -d "$import_tmp/out" "${import_sources[@]}" \
  "$import_stage/scripts/OrganizationAccountImportSourceTest.java" \
  "$import_stage/scripts/OrganizationAccountImportTest.java"
"$import_java" -cp "$import_tmp/out:$import_app/lib/h2.jar" \
  com.training.OrganizationAccountImportSourceTest
"$import_java" -cp "$import_tmp/out:$import_app/lib/h2.jar" \
  com.training.OrganizationAccountImportTest "$import_tmp/data" "$import_tmp/sources"
