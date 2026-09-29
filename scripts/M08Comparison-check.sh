#!/usr/bin/env bash
set -euo pipefail
m08_script="$(cd "$(dirname "$0")" && pwd)"
m08_app="${M08_COMPARISON_APP_ROOT:-$(cd "$m08_script/.." && pwd)}"
m08_src="${M08_COMPARISON_SOURCE:-$m08_app/src/com/training/TrainingSummariesIntegration.java}"
m08_java="${M08_JAVA:-java}"
m08_tmp="$(mktemp -d "${TMPDIR:-/tmp}/m08-comparison.XXXXXXXX")"
trap 'rm -rf -- "$m08_tmp"' EXIT
mkdir "$m08_tmp/classes" "$m08_tmp/data"
m08_cp="$m08_app/lib/h2.jar:$m08_app/lib/pdfbox-app-3.0.8.jar:$m08_app/lib/ip2region-3.3.7.jar"
m08_sources=()
for f in "$m08_app"/src/com/training/*.java; do
  case "${f##*/}" in TrainingSummariesIntegration.java) continue;; esac
  m08_sources+=("$f")
done
"$m08_java" -jar "$m08_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m08_cp" -d "$m08_tmp/classes" "${m08_sources[@]}" "$m08_src" "$m08_app/scripts/M08DeliverySourceTest.java" "$m08_script/M08ComparisonTest.java"
# Reuses only the existing synthetic fixture helpers, not the old test runner or Db.init.
"$m08_java" -Xmx256m -Dfile.encoding=UTF-8 -cp "$m08_tmp/classes:$m08_cp" com.training.M08ComparisonTest "$m08_tmp/data"
