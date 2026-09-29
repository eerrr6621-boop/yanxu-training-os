#!/usr/bin/env bash
set -euo pipefail
M07_APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M07_JAVA="${M07_JAVA:-java}"
M07_TMP="$(mktemp -d "${TMPDIR:-/tmp}/yx-M07-response.XXXXXX")"
trap 'rm -rf "$M07_TMP"' EXIT
"$M07_JAVA" -jar "$M07_APP_ROOT/lib/ecj.jar" -17 -encoding UTF-8 -proc:none -d "$M07_TMP" \
  "$M07_APP_ROOT/src/com/training/SurveySummaryImports.java" \
  "$M07_APP_ROOT/src/com/training/SurveySummaryImportsResponses.java" \
  "$M07_APP_ROOT/scripts/M07ResponseImportsTest.java"
"$M07_JAVA" -Xmx512m -cp "$M07_TMP" com.training.M07ResponseImportsTest
