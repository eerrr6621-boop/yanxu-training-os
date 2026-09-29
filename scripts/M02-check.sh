#!/usr/bin/env bash
set -euo pipefail
M02_APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M02_JAVA="${M02_JAVA:-java}"
M02_OUT="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m02-check.XXXXXX")"
trap 'rm -rf "$M02_OUT"' EXIT
"$M02_JAVA" -jar "$M02_APP_ROOT/lib/ecj.jar" -17 -encoding UTF-8 -d "$M02_OUT" \
  "$M02_APP_ROOT/src/com/training/Json.java" \
  "$M02_APP_ROOT/src/com/training/DemandIntake.java" \
  "$M02_APP_ROOT/src/com/training/DeliverySettlement.java" \
  "$M02_APP_ROOT/src/com/training/DeliverySettlementHours.java" \
  "$M02_APP_ROOT/scripts/M02DemandIntakeTest.java"
"$M02_JAVA" -cp "$M02_OUT" com.training.M02DemandIntakeTest
