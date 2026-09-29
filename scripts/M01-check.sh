#!/usr/bin/env bash
set -euo pipefail
module_root="$(cd "$(dirname "$0")/.." && pwd)"
module_java="${M01_JAVA:-java}"
module_compiler="${M01_ECJ:-$module_root/lib/ecj.jar}"
module_out="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m01-check.XXXXXX")"
"$module_java" -jar "$module_compiler" -17 -encoding UTF-8 -d "$module_out" \
  "$module_root/src/com/training/OrganizationAccess.java" \
  "$module_root/src/com/training/OrganizationAccessDemo.java" \
  "$module_root/scripts/M01OrganizationAccessTest.java"
"$module_java" -cp "$module_out" com.training.M01OrganizationAccessTest
node --input-type=module --check < "$module_root/web/modules/identity/index.js"
node --input-type=module --check < "$module_root/web/modules/identity/fixture.js"
node "$module_root/scripts/M01-identity-check.mjs"
printf 'M01 isolated test classes: %s\n' "$module_out"
