#!/usr/bin/env bash
set -euo pipefail
m04_stage="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -n "${M04_SHARED_APP:-}" ]]; then
  m04_shared="$M04_SHARED_APP"
elif [[ -f "$m04_stage/lib/ecj.jar" && -f "$m04_stage/src/com/training/Db.java" ]]; then
  m04_shared="$m04_stage"
else
  m04_shared="$m04_stage"
fi
m04_java="${M04_INTEGRATION_JAVA:-java}"
m04_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m04-integration.XXXXXX")"
# This directory is created by this run and contains only synthetic test data/classes.
trap 'rm -rf "$m04_tmp"' EXIT
mkdir -p "$m04_tmp/out" "$m04_tmp/data"
m04_sources=()
for m04_source in "$m04_shared"/src/com/training/*.java; do
  case "${m04_source##*/}" in CourseCatalog.java|CourseCatalogIntegration.java) continue ;; esac
  m04_sources+=("$m04_source")
done
"$m04_java" -jar "$m04_shared/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$m04_shared/lib/h2.jar:$m04_shared/lib/pdfbox-app-3.0.8.jar:$m04_shared/lib/ip2region-3.3.7.jar" \
  -d "$m04_tmp/out" "${m04_sources[@]}" \
  "$m04_stage/src/com/training/CourseCatalog.java" \
  "$m04_stage/src/com/training/CourseCatalogIntegration.java" \
  "$m04_stage/scripts/M04IntegrationTest.java"
"$m04_java" -cp "$m04_tmp/out:$m04_shared/lib/h2.jar" com.training.M04IntegrationTest "$m04_tmp/data"
