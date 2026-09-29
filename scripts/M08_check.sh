#!/usr/bin/env bash
set -euo pipefail
M08_APP="$(cd "$(dirname "$0")/.." && pwd)"
M08_JAVA="${M08_JAVA:-java}"
M08_TMP="$(mktemp -d "${TMPDIR:-/tmp}/m08-check.XXXXXXXX")"
trap 'rm -rf -- "$M08_TMP"' EXIT
"$M08_JAVA" -jar "$M08_APP/lib/ecj.jar" -17 -encoding UTF-8 -d "$M08_TMP" \
  "$M08_APP/src/com/training/Json.java" "$M08_APP/src/com/training/TrainingSummaries.java" "$M08_APP/scripts/M08DomainTest.java"
"$M08_JAVA" -Dfile.encoding=UTF-8 -cp "$M08_TMP" com.training.M08DomainTest "$@"
