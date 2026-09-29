#!/usr/bin/env bash
set -euo pipefail
S01_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S01_JAVA="${S01_JAVA:-java}"
S01_LIBRARY_ROOT="${S01_LIBRARY_ROOT:-$S01_ROOT}"
S01_TMP="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-check.XXXXXX")"
trap 'rm -rf "$S01_TMP"' EXIT
"$S01_JAVA" -jar "$S01_LIBRARY_ROOT/lib/ecj.jar" -17 -encoding UTF-8 -d "$S01_TMP" \
  "$S01_LIBRARY_ROOT/src/com/training/Json.java" \
  "$S01_ROOT/src/com/training/NotificationChannels.java" \
  "$S01_ROOT/scripts/S01NotificationChannelsTest.java"
"$S01_JAVA" -cp "$S01_TMP" com.training.S01NotificationChannelsTest
