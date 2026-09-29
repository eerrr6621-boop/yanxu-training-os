#!/usr/bin/env bash
# Actual Main/Api with synthetic fixtures, never the working preview database.
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
supplement_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-management-supplement-http-test.XXXXXX")"
mkdir "$supplement_root/out"
supplement_cp="$supplement_classes:$supplement_app/lib/h2.jar:$supplement_app/lib/pdfbox-app-3.0.8.jar:$supplement_app/lib/ip2region-3.3.7.jar"
"$supplement_java" -jar "$supplement_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$supplement_cp" -d "$supplement_root/out" \
  "$supplement_app/scripts/ManagementSupplementHttpFixture.java" \
  "$supplement_app/scripts/ManagementSupplementHttpTest.java" \
  "$supplement_app/scripts/OrganizationAccountImportSourceTest.java" \
  "$supplement_app/scripts/ManagementSupplementSourceTest.java"
cd "$supplement_app"
supplement_cp="$supplement_root/out:$supplement_cp"
"$supplement_java" -cp "$supplement_cp" com.training.ManagementSupplementHttpFixture "$supplement_root"
"$supplement_java" -cp "$supplement_cp" com.training.ManagementSupplementHttpTest "$supplement_root"
printf 'Synthetic HTTP artifacts: %s\n' "$supplement_root"
