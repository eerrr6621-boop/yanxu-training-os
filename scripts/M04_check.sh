#!/usr/bin/env bash
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M04_TMP="$(mktemp -d "${TMPDIR:-/tmp}/M04-check.XXXXXX")"
# Keep the small, task-specific class directory for inspection; never clean other workspaces.
JAVA_BIN="${JAVA_BIN:-java}"
NODE_BIN="${NODE_BIN:-node}"
"$JAVA_BIN" -jar "$APP_ROOT/lib/ecj.jar" -17 -encoding UTF-8 -d "$M04_TMP" \
  "$APP_ROOT/src/com/training/Json.java" "$APP_ROOT/src/com/training/CourseCatalog.java" "$APP_ROOT/scripts/M04CourseCatalogTest.java"
"$JAVA_BIN" -cp "$M04_TMP" com.training.M04CourseCatalogTest
"$NODE_BIN" "$APP_ROOT/scripts/M04_ui_test.mjs"
JAVA_BIN="$JAVA_BIN" "$NODE_BIN" "$APP_ROOT/scripts/M04_conformance_test.mjs" "$M04_TMP"
printf 'M04 isolated verification classes: %s\n' "$M04_TMP"
