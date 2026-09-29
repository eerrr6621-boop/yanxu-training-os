#!/usr/bin/env bash
set -euo pipefail
m08_script="$(cd "$(dirname "$0")" && pwd)"
m08_app="${M08_DELIVERY_APP_ROOT:-$(cd "$m08_script/.." && pwd)}"
m08_src="${M08_DELIVERY_SOURCE_DIR:-$m08_app/src/com/training}"
m08_java="${M08_JAVA:-java}"
m08_tmp="$(mktemp -d "${TMPDIR:-/tmp}/m08-delivery.XXXXXXXX")"
trap 'rm -rf -- "$m08_tmp"' EXIT
mkdir "$m08_tmp/classes" "$m08_tmp/data"
m08_cp="$m08_app/lib/h2.jar:$m08_app/lib/pdfbox-app-3.0.8.jar:$m08_app/lib/ip2region-3.3.7.jar"
m08_sources=()
for f in "$m08_app"/src/com/training/*.java; do
  case "${f##*/}" in TrainingSummariesIntegration.java|TrainingSummariesDeliverySource.java|DeliverySettlementIntegration.java) continue;; esac
  m08_sources+=("$f")
done
for f in TrainingSummariesIntegration.java TrainingSummariesDeliverySource.java DeliverySettlementIntegration.java; do m08_sources+=("$m08_src/$f"); done
"$m08_java" -jar "$m08_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m08_cp" -d "$m08_tmp/classes" "${m08_sources[@]}" "$m08_script/M08DeliverySourceTest.java"
"$m08_java" -Xmx256m -Dfile.encoding=UTF-8 -cp "$m08_tmp/classes:$m08_cp" com.training.M08DeliverySourceTest "$m08_tmp/data" "$m08_script"
