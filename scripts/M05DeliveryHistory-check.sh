#!/usr/bin/env bash
set -euo pipefail
m05_history_script_dir="$(cd "$(dirname "$0")" && pwd)"
m05_history_root="${M05_HISTORY_APP_ROOT:-$(cd "$m05_history_script_dir/.." && pwd)}"
m05_history_java="${M05_HISTORY_JAVA:-java}"
[[ -f "$m05_history_root/src/com/training/DeliverySettlementIntegration.java" ]] || { echo 'M05_HISTORY_APP_ROOT must point to the actual app source directory.' >&2; exit 2; }
m05_history_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m05-history.XXXXXX")"
# This invocation owns only the fresh temporary compile output and synthetic H2 database.
trap 'rm -rf "$m05_history_tmp"' EXIT
mkdir -p "$m05_history_tmp/classes" "$m05_history_tmp/data"
m05_history_classpath="$m05_history_root/lib/h2.jar:$m05_history_root/lib/pdfbox-app-3.0.8.jar:$m05_history_root/lib/ip2region-3.3.7.jar"
"$m05_history_java" -jar "$m05_history_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m05_history_classpath" -d "$m05_history_tmp/classes" \
  "$m05_history_root"/src/com/training/*.java "$m05_history_script_dir/M05DeliveryHistoryTest.java"
"$m05_history_java" -Xmx512m -cp "$m05_history_tmp/classes:$m05_history_classpath" \
  com.training.M05DeliveryHistoryTest "$m05_history_tmp/data"
