#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
m05_java="${M05_JAVA:-java}"
m05_test_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m05-check.XXXXXX")"
# This directory contains only compiler output created by this invocation.
trap 'rm -rf -- "$m05_test_root"' EXIT
"$m05_java" -jar lib/ecj.jar -17 -encoding UTF-8 -d "$m05_test_root" \
  src/com/training/Json.java src/com/training/DeliverySettlement.java src/com/training/DeliverySettlementPolicy.java src/com/training/DeliverySettlementHours.java src/com/training/DeliverySettlementApprovedPolicy.java \
  scripts/M05DeliverySettlementTest.java scripts/M05DeliverySettlementDemo.java scripts/M05DeliverySettlementPolicyTest.java scripts/M05DeliverySettlementHoursTest.java scripts/M05ApprovedPolicyTest.java
"$m05_java" -cp "$m05_test_root" com.training.M05DeliverySettlementTest
"$m05_java" -cp "$m05_test_root" com.training.M05DeliverySettlementPolicyTest
"$m05_java" -cp "$m05_test_root" com.training.M05DeliverySettlementHoursTest
"$m05_java" -cp "$m05_test_root" com.training.M05ApprovedPolicyTest
if [[ "${1:-}" == "--with-m06" ]]; then
  "$m05_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$m05_test_root" -d "$m05_test_root" \
    src/com/training/ManagementReports.java scripts/M05StatisticsBridgeTest.java
  "$m05_java" -cp "$m05_test_root" com.training.M05StatisticsBridgeTest
fi
if command -v node >/dev/null 2>&1; then
  node --input-type=module --check < web/modules/settlement/index.js
  node --input-type=module --check < web/modules/settlement/conversion.js
  node scripts/M05_compare_hours.mjs "$m05_java" "$m05_test_root"
fi
