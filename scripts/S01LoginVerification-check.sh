#!/usr/bin/env bash
set -euo pipefail
s01_login_script_dir="$(cd "$(dirname "$0")" && pwd)"
s01_login_root="${APP_ROOT:-$(cd "$s01_login_script_dir/.." && pwd)}"
s01_login_java="${S01_LOGIN_JAVA:-java}"
s01_login_java="$(command -v "$s01_login_java")" || { echo 'Java 17 executable was not found on PATH or at the configured location.' >&2; exit 2; }
s01_login_source="${S01_LOGIN_SOURCE:-$s01_login_script_dir/../src/com/training/NotificationChannelsLoginVerification.java}"
s01_login_preparation="${S01_LOGIN_PREPARATION_SOURCE:-$s01_login_script_dir/../src/com/training/NotificationChannelsAccountEmailPreparation.java}"
[[ -f "$s01_login_source" ]] || s01_login_source="$s01_login_root/src/com/training/NotificationChannelsLoginVerification.java"
[[ -f "$s01_login_preparation" ]] || s01_login_preparation="$s01_login_root/src/com/training/NotificationChannelsAccountEmailPreparation.java"
[[ -x "$s01_login_java" && -f "$s01_login_root/lib/ecj.jar" && -f "$s01_login_root/lib/h2.jar" && -f "$s01_login_source" && -f "$s01_login_preparation" ]] || {
  echo 'Set APP_ROOT to the app directory; Java, ECJ, H2, and both verification sources are required.' >&2
  exit 2
}
s01_login_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-login-verification.XXXXXX")"
# Remove only this invocation's fresh classes and synthetic test database.
trap 'rm -rf "$s01_login_tmp"' EXIT
mkdir "$s01_login_tmp/classes" "$s01_login_tmp/data"
: > "$s01_login_tmp/.s01-login-verification-test-root"
s01_login_classpath="$s01_login_root/lib/h2.jar:$s01_login_root/lib/pdfbox-app-3.0.8.jar:$s01_login_root/lib/ip2region-3.3.7.jar"
s01_login_sources=()
for s01_login_file in "$s01_login_root"/src/com/training/*.java; do
  case "$s01_login_file" in */NotificationChannelsAccountEmailPreparation.java|*/NotificationChannelsLoginVerification.java) continue ;; esac
  s01_login_sources+=("$s01_login_file")
done
"$s01_login_java" -jar "$s01_login_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$s01_login_classpath" -d "$s01_login_tmp/classes" \
  "${s01_login_sources[@]}" "$s01_login_preparation" "$s01_login_source" "$s01_login_script_dir/S01LoginVerificationTest.java"
# Set the database path before JVM startup, then independently validate it before touching Db.
# Even a failed setup executes from this temporary root, never from the application's data path.
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
cd "$s01_login_tmp"
mkdir "$s01_login_tmp/rejected-setup"
if (cd "$s01_login_tmp/rejected-setup" && "$s01_login_java" \
  -cp "$s01_login_tmp/classes:$s01_login_classpath" \
  com.training.S01LoginVerificationTest "$s01_login_tmp/data") > "$s01_login_tmp/rejected-setup.log" 2>&1; then
  echo 'Unsafe test setup unexpectedly succeeded without an explicit JVM database directory.' >&2
  exit 1
fi
[[ ! -e "$s01_login_tmp/rejected-setup/data" && ! -e "$s01_login_tmp/data/training.mv.db" ]] || {
  echo 'Rejected test setup must not initialize any database.' >&2
  exit 1
}
"$s01_login_java" -Ddata.dir="$s01_login_tmp/data" \
  -cp "$s01_login_tmp/classes:$s01_login_classpath" \
  com.training.S01LoginVerificationTest "$s01_login_tmp/data"
