#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
m06_java="${M06_JAVA:-java}"
m06_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-M06.XXXXXX")"
"$m06_java" -jar lib/ecj.jar -17 -encoding UTF-8 -d "$m06_tmp" src/com/training/Json.java src/com/training/ManagementReports.java src/com/training/ManagementReportsWorkbook.java scripts/M06ReportsTest.java scripts/M06ReportsProbe.java scripts/M06WorkbookTest.java
"$m06_java" -cp "$m06_tmp" com.training.M06ReportsTest >&2
"$m06_java" -cp "$m06_tmp" com.training.M06WorkbookTest >&2
node --test scripts/M06_monthly_test.mjs >&2
if [[ -f scripts/M06_compare_frontend.mjs ]]; then
  node scripts/M06_compare_frontend.mjs "$m06_java" "$m06_tmp"
fi
if [[ "${1:-}" == "--demo" ]]; then
  "$m06_java" -cp "$m06_tmp" com.training.M06ReportsTest --demo
fi
if [[ "${1:-}" == "--demo-csv" ]]; then
  "$m06_java" -cp "$m06_tmp" com.training.M06ReportsTest --demo-csv
fi
