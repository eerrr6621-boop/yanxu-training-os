#!/usr/bin/env bash
# Synthetic legacy -> supplied release classes only. Does not accept an existing DB.
# Usage: bash scripts/check_release_migration.sh --java /path/to/java --classes /path/to/release/out
set -euo pipefail
cd "$(dirname "$0")/.."
release_java="${TRAINING_CHECK_JAVA:-java}"
release_classes=""
while (($#)); do
  case "$1" in
    --java) [[ $# -ge 2 ]] || exit 2; release_java="$2"; shift 2 ;;
    --classes) [[ $# -ge 2 ]] || exit 2; release_classes="$2"; shift 2 ;;
    *) echo 'Usage: check_release_migration.sh [--java /path/to/java] --classes /path/to/release/out' >&2; exit 2 ;;
  esac
done
[[ -n "$release_classes" && -f "$release_classes/com/training/Db.class" ]] || {
  echo 'A separately compiled release --classes directory is required; this gate does not compile app sources.' >&2
  exit 2
}
release_classes="$(cd "$release_classes" && pwd -P)"
# JVM-injected agents/options could alter data paths or load unrelated startup code.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
release_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-release-migration.XXXXXX")"
mkdir "$release_root/out"
printf 'Synthetic migration evidence: %s\nRelease classes: %s\n' "$release_root" "$release_classes"
"$release_java" -jar lib/ecj.jar -17 -encoding UTF-8 \
  -cp "$release_classes:lib/h2.jar" -d "$release_root/out" scripts/ReleaseMigrationCheck.java \
  2>&1 | tee "$release_root/compile.log"
"$release_java" -Dfile.encoding=UTF-8 -Dbootstrap.demo=false \
  -cp "$release_root/out:$release_classes:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" \
  com.training.ReleaseMigrationCheck --work-root "$release_root" \
  2>&1 | tee "$release_root/check.log"
