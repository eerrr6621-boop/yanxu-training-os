#!/usr/bin/env bash
set -euo pipefail
s01_email_script_dir="$(cd "$(dirname "$0")" && pwd)"
s01_email_root="${APP_ROOT:-$(cd "$s01_email_script_dir/.." && pwd)}"
s01_email_java="${S01_EMAIL_JAVA:-java}"
s01_email_source="${S01_EMAIL_SOURCE:-$s01_email_script_dir/../src/com/training/NotificationChannelsAccountEmailPreparation.java}"
if [[ ! -f "$s01_email_source" ]]; then
  s01_email_source="$s01_email_root/src/com/training/NotificationChannelsAccountEmailPreparation.java"
fi
[[ -f "$s01_email_root/lib/h2.jar" && -f "$s01_email_source" ]] || {
  echo 'Set APP_ROOT to the app directory; the email preparation source and H2 are required.' >&2
  exit 2
}
s01_email_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-email-preparation.XXXXXX")"
# Only this invocation's fresh classes and synthetic database are removed.
trap 'rm -rf "$s01_email_tmp"' EXIT
mkdir -p "$s01_email_tmp/classes" "$s01_email_tmp/data"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
s01_email_classpath="$s01_email_root/lib/h2.jar:$s01_email_root/lib/pdfbox-app-3.0.8.jar:$s01_email_root/lib/ip2region-3.3.7.jar"
s01_email_sources=()
for s01_email_file in "$s01_email_root"/src/com/training/*.java; do
  case "$s01_email_file" in */NotificationChannelsAccountEmailPreparation.java) continue ;; esac
  s01_email_sources+=("$s01_email_file")
done
"$s01_email_java" -jar "$s01_email_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$s01_email_classpath" -d "$s01_email_tmp/classes" \
  "${s01_email_sources[@]}" "$s01_email_source" "$s01_email_script_dir/S01AccountEmailPreparationTest.java"
"$s01_email_java" -cp "$s01_email_tmp/classes:$s01_email_classpath" \
  com.training.S01AccountEmailPreparationTest "$s01_email_tmp/data"
# New process, same synthetic database, after the first process has shut H2 down.
"$s01_email_java" -cp "$s01_email_tmp/classes:$s01_email_classpath" \
  com.training.S01AccountEmailPreparationTest --reopen "$s01_email_tmp/data"
