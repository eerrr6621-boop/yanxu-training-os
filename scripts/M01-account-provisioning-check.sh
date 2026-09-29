#!/usr/bin/env bash
set -euo pipefail
provisioning_stage="$(cd "$(dirname "$0")/.." && pwd)"
provisioning_app="${M01_PROVISIONING_APP_ROOT:-$provisioning_stage}"
provisioning_java="${M01_PROVISIONING_JAVA:-java}"
provisioning_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-account-provisioning.XXXXXX")"
# Remove only this runner's fresh synthetic tree, after its JVM has exited.
trap 'rm -rf "$provisioning_tmp"' EXIT
mkdir -p "$provisioning_tmp/out" "$provisioning_tmp/data" "$provisioning_tmp/sources"
provisioning_sources=()
for provisioning_file in "$provisioning_app"/src/com/training/*.java; do
  if [[ ! -f "$provisioning_stage/src/com/training/$(basename "$provisioning_file")" ]]; then
    provisioning_sources+=("$provisioning_file")
  fi
done
for provisioning_file in "$provisioning_stage"/src/com/training/*.java; do
  provisioning_sources+=("$provisioning_file")
done
"$provisioning_java" -jar "$provisioning_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$provisioning_app/lib/h2.jar:$provisioning_app/lib/pdfbox-app-3.0.8.jar:$provisioning_app/lib/ip2region-3.3.7.jar" \
  -d "$provisioning_tmp/out" "${provisioning_sources[@]}" \
  "$provisioning_app/scripts/OrganizationAccountImportSourceTest.java" \
  "$provisioning_stage/scripts/M01AccountProvisioningTest.java"
"$provisioning_java" -cp "$provisioning_tmp/out:$provisioning_app/lib/h2.jar" \
  com.training.M01AccountProvisioningTest "$provisioning_tmp"
