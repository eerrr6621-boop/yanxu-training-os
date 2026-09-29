#!/usr/bin/env bash
# Test the supplied product build without replacing any product classes or using app/data.
set -euo pipefail
coding_app="$(cd "$(dirname "$0")/.." && pwd)"
coding_java="${TRAINING_CHECK_JAVA:-java}"
coding_python="${TRAINING_CHECK_PYTHON:-python3}"
coding_classes=""
coding_artifacts=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --java) coding_java="$2"; shift 2;;
    --classes) coding_classes="$2"; shift 2;;
    --artifacts) coding_artifacts="$2"; shift 2;;
    *) echo "Unknown option: $1" >&2; exit 2;;
  esac
done
[[ -f "$coding_classes/com/training/DeliverySettlementCoding.class" ]] || { echo 'Integrated product classes required' >&2; exit 2; }
coding_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-coding-integrated.XXXXXX")"
coding_artifacts="${coding_artifacts:-$coding_root/evidence}"
mkdir -p "$coding_root/test-out" "$coding_root/standard-data" "$coding_root/long-data" "$coding_root/flow-data" "$coding_artifacts"
coding_cp="$coding_classes:$coding_app/lib/h2.jar:$coding_app/lib/pdfbox-app-3.0.8.jar:$coding_app/lib/ip2region-3.3.7.jar"
"$coding_java" -jar "$coding_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$coding_cp" -d "$coding_root/test-out" \
  "$coding_app/scripts/M05FormalWorkflowTest.java" "$coding_app/scripts/M05CasesTest.java" "$coding_app/scripts/M05CodingTest.java" \
  "$coding_app/scripts/M06SettlementCodingContractTest.java" "$coding_app/scripts/M06SettlementAccountingTest.java" \
  "$coding_app/scripts/M06SettlementCodingFlowTest.java" "$coding_app/scripts/M06SettlementBridgeTest.java"
export JAVA_TOOL_OPTIONS='' JDK_JAVA_OPTIONS='' _JAVA_OPTIONS='' YANXU_LOGIN_MAIL_TRANSPORT=disabled YANXU_CITY_PLANNING_COLLECTION_FILE=''
coding_test_cp="$coding_root/test-out:$coding_cp"
"$coding_java" -Dlogin.email.mode=legacy -cp "$coding_test_cp" com.training.M05CodingTest "$coding_root/standard-data" "$coding_artifacts/m05" standard
"$coding_java" -Dlogin.email.mode=legacy -cp "$coding_test_cp" com.training.M05CodingTest "$coding_root/long-data" "$coding_artifacts/long-history" long-create
"$coding_java" -Dlogin.email.mode=legacy -cp "$coding_test_cp" com.training.M05CodingTest "$coding_root/long-data" "$coding_artifacts/long-history" long-replay
"$coding_java" -cp "$coding_test_cp" com.training.M06SettlementCodingContractTest "$coding_artifacts/m06"
"$coding_java" -cp "$coding_test_cp" com.training.M06SettlementAccountingTest
"$coding_java" -Dlogin.email.mode=legacy -cp "$coding_test_cp" com.training.M06SettlementCodingFlowTest "$coding_root/flow-data" "$coding_artifacts/m06"
"$coding_python" "$coding_app/scripts/check_settlement_coding_workbooks.py" "$coding_artifacts/m06"
printf 'Integrated coding evidence: %s\n' "$coding_artifacts"
