#!/usr/bin/env bash
set -euo pipefail
identity_root="$(cd "$(dirname "$0")/.." && pwd)"
identity_java="${INTEGRATION_IDENTITY_JAVA:-java}"
identity_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-identity-check.XXXXXX")"
# This trap only removes this script's fresh isolated test artifacts after the JVM exits.
trap 'rm -rf "$identity_tmp"' EXIT
mkdir -p "$identity_tmp/out" "$identity_tmp/data"
"$identity_java" -jar "$identity_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$identity_root/lib/h2.jar:$identity_root/lib/pdfbox-app-3.0.8.jar:$identity_root/lib/ip2region-3.3.7.jar" \
  -d "$identity_tmp/out" "$identity_root"/src/com/training/*.java "$identity_root/scripts/IntegrationIdentityTest.java"
"$identity_java" -cp "$identity_tmp/out:$identity_root/lib/h2.jar" com.training.IntegrationIdentityTest "$identity_tmp/data"
