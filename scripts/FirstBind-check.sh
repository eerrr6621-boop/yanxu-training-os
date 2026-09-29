#!/usr/bin/env bash
set -euo pipefail
umask 077
export YANXU_LOGIN_MAIL_TRANSPORT=disabled JAVA_TOOL_OPTIONS= JDK_JAVA_OPTIONS= _JAVA_OPTIONS=
for first_env in ${!YANXU_LOGIN_SMTP_@}; do unset "$first_env"; done
first_scripts="$(cd "$(dirname "$0")" && pwd)"
first_app="${APP_ROOT:-$(cd "$first_scripts/.." && pwd)}"
first_java="${FIRST_BIND_JAVA:-java}"
first_products="${FIRST_BIND_PRODUCT_CLASSES:-}"
command -v "$first_java" >/dev/null
[[ -f "$first_app/lib/ecj.jar" && -f "$first_app/lib/h2.jar" && -f "$first_app/src/com/training/FirstBindStore.java" ]] || exit 2
if [[ -n "$first_products" ]]; then
  [[ -f "$first_products/com/training/FirstBindStore.class" && -f "$first_products/com/training/Auth.class" ]] || exit 2
  first_products="$(cd "$first_products" && pwd)"
fi
first_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-first-bind-r26.XXXXXX")"
first_tmp="$(cd "$first_tmp" && pwd -P)"
# Remove only this invocation's owned fixture classes and synthetic databases.
trap 'rm -rf "$first_tmp"' EXIT
mkdir "$first_tmp/classes" "$first_tmp/data" "$first_tmp/rejected-setup" "$first_tmp/preparation" "$first_tmp/preparation/data"
: > "$first_tmp/.first-bind-test-root"
: > "$first_tmp/preparation/.preparation-test-owned"
first_libs="$first_app/lib/h2.jar:$first_app/lib/pdfbox-app-3.0.8.jar:$first_app/lib/ip2region-3.3.7.jar"
if [[ -z "$first_products" ]]; then
  first_products="$first_tmp/product-classes"
  mkdir "$first_products"
  "$first_java" -jar "$first_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$first_libs" -d "$first_products" "$first_app"/src/com/training/*.java
fi
"$first_java" -jar "$first_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$first_products:$first_libs" -d "$first_tmp/classes" "$first_scripts/FirstBindTest.java" "$first_scripts/FirstBindPreparationTest.java"
first_cp="$first_tmp/classes:$first_products:$first_libs"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
cd "$first_tmp"
if (cd "$first_tmp/rejected-setup" && "$first_java" -cp "$first_cp" com.training.FirstBindTest "$first_tmp/data") > "$first_tmp/rejected-setup.log" 2>&1; then
  echo 'Setup must reject missing explicit data.dir.' >&2; exit 1
fi
[[ ! -e "$first_tmp/rejected-setup/data" && ! -e "$first_tmp/data/training.mv.db" ]] || exit 1
"$first_java" -Ddata.dir="$first_tmp/data" -cp "$first_cp" com.training.FirstBindTest "$first_tmp/data"
"$first_java" -Ddata.dir="$first_tmp/data" -cp "$first_cp" com.training.FirstBindTest --reopen "$first_tmp/data"
"$first_java" -Ddata.dir="$first_tmp/preparation/data" -cp "$first_cp" com.training.FirstBindPreparationTest "$first_tmp/preparation/data"
