#!/usr/bin/env bash
# Test only with new synthetic stores; supplied product classes remain read-only.
set -euo pipefail
supplement_app="$(cd "$(dirname "$0")/.." && pwd)"
supplement_java="${TRAINING_CHECK_JAVA:-java}"
supplement_classes=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --java) supplement_java="$2"; shift 2;;
    --classes) supplement_classes="$2"; shift 2;;
    *) echo "Unknown option: $1" >&2; exit 2;;
  esac
done
[[ -f "$supplement_classes/com/training/OrganizationManagementSupplementHttp.class" ]] || { echo 'Integrated product classes required' >&2; exit 2; }
supplement_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-management-supplement-core.XXXXXX")"
mkdir -p "$supplement_root/out" "$supplement_root/source" "$supplement_root/intake-data" "$supplement_root/intake-sources" "$supplement_root/old-data" "$supplement_root/old-sources" "$supplement_root/yanxu-m01-account-provisioning.regression"
supplement_cp="$supplement_classes:$supplement_app/lib/h2.jar:$supplement_app/lib/pdfbox-app-3.0.8.jar:$supplement_app/lib/ip2region-3.3.7.jar"
"$supplement_java" -jar "$supplement_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$supplement_cp" -d "$supplement_root/out" \
  "$supplement_app/scripts/ManagementSupplementSourceTest.java" \
  "$supplement_app/scripts/ManagementSupplementIntakeTest.java" \
  "$supplement_app/scripts/OrganizationAccountImportSourceTest.java" \
  "$supplement_app/scripts/OrganizationAccountImportTest.java" \
  "$supplement_app/scripts/M01AccountProvisioningTest.java"
supplement_cp="$supplement_root/out:$supplement_cp"
cd "$supplement_root"
"$supplement_java" -Dlogin.email.mode=legacy -cp "$supplement_cp" com.training.ManagementSupplementSourceTest "$supplement_root/source"
"$supplement_java" -Dlogin.email.mode=legacy -cp "$supplement_cp" com.training.ManagementSupplementIntakeTest "$supplement_root/intake-data" "$supplement_root/intake-sources"
"$supplement_java" -Dlogin.email.mode=legacy -cp "$supplement_cp" com.training.OrganizationAccountImportSourceTest
"$supplement_java" -Dlogin.email.mode=legacy -cp "$supplement_cp" com.training.OrganizationAccountImportTest "$supplement_root/old-data" "$supplement_root/old-sources"
"$supplement_java" -Dlogin.email.mode=legacy -cp "$supplement_cp" com.training.M01AccountProvisioningTest "$supplement_root/yanxu-m01-account-provisioning.regression"
printf 'Synthetic test artifacts: %s\n' "$supplement_root"
