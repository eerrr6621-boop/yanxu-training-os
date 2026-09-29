#!/usr/bin/env bash
set -euo pipefail
host_stage="$(cd "$(dirname "$0")/.." && pwd)"
host_app="${M01_IMPORT_APP_ROOT:-$host_stage}"
host_java="${M01_IMPORT_JAVA:-java}"
host_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-import-host.XXXXXX")"
# The test closes each child Main before return; only this newly created fixture is removed.
trap 'rm -rf "$host_tmp"' EXIT
mkdir -p "$host_tmp/out"
host_sources=()
for host_file in "$host_app"/src/com/training/*.java; do
  if [[ ! -f "$host_stage/src/com/training/$(basename "$host_file")" ]]; then host_sources+=("$host_file"); fi
done
for host_file in "$host_stage"/src/com/training/*.java; do host_sources+=("$host_file"); done
"$host_java" -jar "$host_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$host_app/lib/h2.jar:$host_app/lib/pdfbox-app-3.0.8.jar:$host_app/lib/ip2region-3.3.7.jar" \
  -d "$host_tmp/out" "${host_sources[@]}" \
  "$host_app/scripts/OrganizationAccountImportSourceTest.java" "$host_stage/scripts/OrganizationAccountImportHostTest.java"
"$host_java" -cp "$host_tmp/out:$host_app/lib/h2.jar:$host_app/lib/pdfbox-app-3.0.8.jar:$host_app/lib/ip2region-3.3.7.jar" \
  com.training.OrganizationAccountImportHostTest "$host_tmp"
