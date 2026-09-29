#!/usr/bin/env bash
set -euo pipefail
display_stage="$(cd "$(dirname "$0")/.." && pwd)"
display_app="${M01_ORGANIZATION_DISPLAY_NAME_APP_ROOT:-$display_stage}"
display_java="${M01_ORGANIZATION_DISPLAY_NAME_JAVA:-java}"
display_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-organization-display-name.XXXXXX")"
trap 'rm -rf "$display_tmp"' EXIT
mkdir -p "$display_tmp/out" "$display_tmp/data" "$display_tmp/sources"
display_sources=()
for display_file in "$display_app"/src/com/training/*.java; do
  display_base="$(basename "$display_file")"
  case "$display_base" in
    OrganizationAccess.java|OrganizationAccessStore.java|OrganizationAccountProvisioning.java)
      display_sources+=("$display_stage/src/com/training/$display_base");;
    *) display_sources+=("$display_file");;
  esac
done
"$display_java" -jar "$display_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$display_app/lib/h2.jar:$display_app/lib/pdfbox-app-3.0.8.jar:$display_app/lib/ip2region-3.3.7.jar" \
  -d "$display_tmp/out" "${display_sources[@]}" \
  "$display_app/scripts/OrganizationAccountImportSourceTest.java" \
  "$display_stage/scripts/M01OrganizationDisplayNameTest.java"
"$display_java" -cp "$display_tmp/out:$display_app/lib/h2.jar" \
  com.training.M01OrganizationDisplayNameTest "$display_tmp"
