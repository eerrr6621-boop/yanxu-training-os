#!/usr/bin/env bash
set -euo pipefail
m06_here="$(cd "$(dirname "$0")" && pwd)"
m06_app="${M06_SETTLEMENT_APP_ROOT:-$(cd "$m06_here/.." && pwd)}"
m06_source="${M06_SETTLEMENT_SOURCE_DIR:-$m06_app/src/com/training}"
m06_m05="${M06_SETTLEMENT_M05_SOURCE_DIR:-$m06_app/src/com/training}"
m06_m01="${M06_SETTLEMENT_M01_SOURCE_DIR:-$m06_app/src/com/training}"
m06_java="${M06_SETTLEMENT_JAVA:-java}"
m06_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m06-settlement-bridge.XXXXXX")"
trap 'rm -rf "$m06_tmp"' EXIT
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
mkdir -p "$m06_tmp/classes" "$m06_tmp/data"
m06_cp="$m06_app/lib/h2.jar:$m06_app/lib/pdfbox-app-3.0.8.jar:$m06_app/lib/ip2region-3.3.7.jar"
m06_sources=()
for m06_file in "$m06_app"/src/com/training/*.java; do
 m06_name="${m06_file##*/}"
 case "$m06_name" in ManagementSettlementAccounting.java|ManagementSettlementBridge.java|ManagementSettlementWorkbook.java) continue;; esac
 if [[ "$m06_m01" != "$m06_app/src/com/training" && -f "$m06_m01/$m06_name" ]]; then continue; fi
 if [[ "$m06_m05" != "$m06_app/src/com/training" && -f "$m06_m05/$m06_name" ]]; then continue; fi
 m06_sources+=("$m06_file")
done
if [[ "$m06_m05" != "$m06_app/src/com/training" ]]; then
 for m06_file in "$m06_m05"/DeliverySettlement*.java; do m06_sources+=("$m06_file"); done
fi
if [[ "$m06_m01" != "$m06_app/src/com/training" ]]; then
 for m06_file in "$m06_m01"/*.java; do m06_sources+=("$m06_file"); done
fi
m06_sources+=("$m06_source/ManagementSettlementAccounting.java" "$m06_source/ManagementSettlementWorkbook.java" "$m06_source/ManagementSettlementBridge.java")
"$m06_java" -jar "$m06_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m06_cp" -d "$m06_tmp/classes" "${m06_sources[@]}" "$m06_here/M06SettlementBridgeTest.java"
"$m06_java" -cp "$m06_tmp/classes:$m06_cp" com.training.M06SettlementBridgeTest "$m06_tmp/data"
