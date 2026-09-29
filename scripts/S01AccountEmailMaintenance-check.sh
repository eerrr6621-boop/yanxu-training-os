#!/usr/bin/env bash
set -euo pipefail
s01_maintenance_scripts="$(cd "$(dirname "$0")" && pwd)"
s01_maintenance_app="${APP_ROOT:-$(cd "$s01_maintenance_scripts/.." && pwd)}"
s01_maintenance_java="${S01_MAINTENANCE_JAVA:-java}"
s01_maintenance_java="$(command -v "$s01_maintenance_java")" || { echo 'Java 17 executable was not found on PATH or at the configured location.' >&2; exit 2; }
s01_maintenance_source="${S01_MAINTENANCE_SOURCE:-$s01_maintenance_scripts/../src/com/training/NotificationChannelsAccountEmailMaintenance.java}"
s01_maintenance_preparation="${S01_MAINTENANCE_PREPARATION_SOURCE:-$s01_maintenance_scripts/../src/com/training/NotificationChannelsAccountEmailPreparation.java}"
[[ -x "$s01_maintenance_java" && -f "$s01_maintenance_app/lib/ecj.jar" && -f "$s01_maintenance_app/lib/h2.jar" && -f "$s01_maintenance_source" && -f "$s01_maintenance_preparation" ]] || {
  echo 'Set APP_ROOT to the app directory; Java, ECJ, H2 and both email sources are required.' >&2
  exit 2
}
s01_maintenance_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-email-maintenance.XXXXXX")"
# Cleanup is limited to this invocation's fresh, owned classes and synthetic database.
trap 'rm -rf "$s01_maintenance_tmp"' EXIT
mkdir "$s01_maintenance_tmp/classes" "$s01_maintenance_tmp/data" "$s01_maintenance_tmp/rejected-setup"
: > "$s01_maintenance_tmp/.s01-email-maintenance-test-root"
s01_maintenance_cp="$s01_maintenance_app/lib/h2.jar:$s01_maintenance_app/lib/pdfbox-app-3.0.8.jar:$s01_maintenance_app/lib/ip2region-3.3.7.jar"
s01_maintenance_sources=()
for s01_maintenance_file in "$s01_maintenance_app"/src/com/training/*.java; do
  case "$s01_maintenance_file" in */NotificationChannelsAccountEmailPreparation.java|*/NotificationChannelsAccountEmailMaintenance.java) continue ;; esac
  s01_maintenance_sources+=("$s01_maintenance_file")
done
"$s01_maintenance_java" -jar "$s01_maintenance_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$s01_maintenance_cp" -d "$s01_maintenance_tmp/classes" \
  "${s01_maintenance_sources[@]}" "$s01_maintenance_preparation" "$s01_maintenance_source" "$s01_maintenance_scripts/S01AccountEmailMaintenanceTest.java"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
cd "$s01_maintenance_tmp"
if (cd "$s01_maintenance_tmp/rejected-setup" && "$s01_maintenance_java" \
  -cp "$s01_maintenance_tmp/classes:$s01_maintenance_cp" \
  com.training.S01AccountEmailMaintenanceTest "$s01_maintenance_tmp/data") > "$s01_maintenance_tmp/rejected-setup.log" 2>&1; then
  echo 'Unsafe test setup unexpectedly succeeded without explicit JVM data.dir.' >&2
  exit 1
fi
[[ ! -e "$s01_maintenance_tmp/rejected-setup/data" && ! -e "$s01_maintenance_tmp/data/training.mv.db" ]] || {
  echo 'Rejected setup must not initialize any database.' >&2
  exit 1
}
"$s01_maintenance_java" -Ddata.dir="$s01_maintenance_tmp/data" -Dlogin.email.mode=legacy \
  -cp "$s01_maintenance_tmp/classes:$s01_maintenance_cp" \
  com.training.S01AccountEmailMaintenanceTest "$s01_maintenance_tmp/data"
"$s01_maintenance_java" -Ddata.dir="$s01_maintenance_tmp/data" -Dlogin.email.mode=legacy \
  -cp "$s01_maintenance_tmp/classes:$s01_maintenance_cp" \
  com.training.S01AccountEmailMaintenanceTest --reopen "$s01_maintenance_tmp/data"
