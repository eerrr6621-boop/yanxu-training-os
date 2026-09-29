#!/usr/bin/env bash
set -euo pipefail
umask 077
export YANXU_LOGIN_MAIL_TRANSPORT=disabled JAVA_TOOL_OPTIONS= JDK_JAVA_OPTIONS= _JAVA_OPTIONS=
for s01_device_env in ${!YANXU_LOGIN_SMTP_@}; do unset "$s01_device_env"; done
s01_device_scripts="$(cd "$(dirname "$0")" && pwd)"
s01_device_app="${APP_ROOT:-$(cd "$s01_device_scripts/.." && pwd)}"
s01_device_products="${TRUSTED_DEVICE_PRODUCT_CLASSES:-}"
s01_device_java="${TRUSTED_DEVICE_JAVA:-java}"
command -v "$s01_device_java" >/dev/null && [[ -f "$s01_device_app/lib/ecj.jar" && -f "$s01_device_app/lib/h2.jar" && -f "$s01_device_app/src/com/training/TrustedDevices.java" ]] || {
  echo 'Current product sources, Java, ECJ and H2 are required.' >&2
  exit 2
}
if [[ -n "$s01_device_products" ]]; then
  [[ -d "$s01_device_products" && -f "$s01_device_products/com/training/TrustedDevices.class" && -f "$s01_device_products/com/training/Auth.class" ]] || {
    echo 'TRUSTED_DEVICE_PRODUCT_CLASSES must be an explicitly built current product directory.' >&2
    exit 2
  }
  s01_device_products="$(cd "$s01_device_products" && pwd)"
fi
s01_device_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-trusted-device-r25.XXXXXX")"
# Only this invocation's owned generated classes, synthetic token fixture and database are removed.
trap 'rm -rf "$s01_device_tmp"' EXIT
mkdir "$s01_device_tmp/classes" "$s01_device_tmp/data" "$s01_device_tmp/rejected-setup"
: > "$s01_device_tmp/.trusted-devices-test-root"
s01_device_libs="$s01_device_app/lib/h2.jar:$s01_device_app/lib/pdfbox-app-3.0.8.jar:$s01_device_app/lib/ip2region-3.3.7.jar"
if [[ -z "$s01_device_products" ]]; then
  s01_device_products="$s01_device_tmp/product-classes"
  mkdir "$s01_device_products"
  "$s01_device_java" -jar "$s01_device_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
    -cp "$s01_device_libs" -d "$s01_device_products" "$s01_device_app"/src/com/training/*.java
fi
# Compile only the fixture into its own directory; supplied product classes are read-only.
"$s01_device_java" -jar "$s01_device_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$s01_device_products:$s01_device_libs" -d "$s01_device_tmp/classes" \
  "$s01_device_scripts/TrustedDevicesTest.java"
s01_device_cp="$s01_device_tmp/classes:$s01_device_products:$s01_device_libs"
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
cd "$s01_device_tmp"
if (cd "$s01_device_tmp/rejected-setup" && "$s01_device_java" -cp "$s01_device_cp" \
  com.training.TrustedDevicesTest "$s01_device_tmp/data") > "$s01_device_tmp/rejected-setup.log" 2>&1; then
  echo 'Setup unexpectedly succeeded without explicit JVM data.dir.' >&2
  exit 1
fi
[[ ! -e "$s01_device_tmp/rejected-setup/data" && ! -e "$s01_device_tmp/data/training.mv.db" ]] || {
  echo 'Rejected setup must not touch a database.' >&2
  exit 1
}
"$s01_device_java" -Ddata.dir="$s01_device_tmp/data" -cp "$s01_device_cp" \
  com.training.TrustedDevicesTest "$s01_device_tmp/data"
"$s01_device_java" -Ddata.dir="$s01_device_tmp/data" -cp "$s01_device_cp" \
  com.training.TrustedDevicesTest --reopen "$s01_device_tmp/data"
