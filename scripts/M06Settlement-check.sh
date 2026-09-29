#!/usr/bin/env bash
set -euo pipefail
m06_here="$(cd "$(dirname "$0")" && pwd)"
m06_app="${M06_SETTLEMENT_APP_ROOT:-$(cd "$m06_here/.." && pwd)}"
m06_source="${M06_SETTLEMENT_SOURCE_DIR:-$m06_app/src/com/training}"
m06_java="${M06_SETTLEMENT_JAVA:-java}"
m06_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m06-settlement.XXXXXX")"
trap 'rm -rf "$m06_tmp"' EXIT
mkdir -p "$m06_tmp/classes"
"$m06_java" -jar "$m06_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -d "$m06_tmp/classes" \
 "$m06_app/src/com/training/Json.java" "$m06_source/ManagementSettlementAccounting.java" "$m06_source/ManagementSettlementWorkbook.java" "$m06_here/M06SettlementAccountingTest.java"
"$m06_java" -cp "$m06_tmp/classes" com.training.M06SettlementAccountingTest "${M06_SETTLEMENT_FIXTURES:-$m06_tmp/fixtures}"
