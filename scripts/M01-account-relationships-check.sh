#!/usr/bin/env bash
set -euo pipefail
relationship_stage="$(cd "$(dirname "$0")/.." && pwd)"
relationship_app="${M01_RELATIONSHIPS_APP_ROOT:-$relationship_stage}"
relationship_java="${M01_RELATIONSHIPS_JAVA:-java}"
relationship_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-account-relationships.XXXXXX")"
trap 'rm -rf "$relationship_tmp"' EXIT
mkdir -p "$relationship_tmp/out"
relationship_sources=()
for relationship_file in "$relationship_app"/src/com/training/*.java; do
 if [[ ! -f "$relationship_stage/src/com/training/$(basename "$relationship_file")" ]]; then relationship_sources+=("$relationship_file"); fi
done
for relationship_file in "$relationship_stage"/src/com/training/*.java; do relationship_sources+=("$relationship_file"); done
"$relationship_java" -jar "$relationship_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
 -cp "$relationship_app/lib/h2.jar:$relationship_app/lib/pdfbox-app-3.0.8.jar:$relationship_app/lib/ip2region-3.3.7.jar" \
 -d "$relationship_tmp/out" "${relationship_sources[@]}" "$relationship_stage/scripts/M01AccountRelationshipsTest.java"
"$relationship_java" -cp "$relationship_tmp/out:$relationship_app/lib/h2.jar" com.training.M01AccountRelationshipsTest "$relationship_tmp"
