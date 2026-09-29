#!/usr/bin/env bash
# Frozen product classes stay read-only; all tests use newly created synthetic stores.
set -euo pipefail
management_app="$(cd "$(dirname "$0")/.." && pwd)"
management_java="${TRAINING_CHECK_JAVA:-java}"
management_python="${TRAINING_CHECK_PYTHON:-python3}"
management_classes=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --java) management_java="$2"; shift 2;;
    --classes) management_classes="$2"; shift 2;;
    *) echo "Unknown option: $1" >&2; exit 2;;
  esac
done
[[ -f "$management_classes/com/training/OrganizationManagementGroupHttp.class" ]] || { echo 'Integrated product classes required' >&2; exit 2; }
management_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-management-group.XXXXXX")"
mkdir -p "$management_root/test-out" "$management_root/main-data" "$management_root/main-sources" \
  "$management_root/actor-data" "$management_root/actor-sources" "$management_root/boundary-data" \
  "$management_root/prepared-data" "$management_root/prepared-sources" "$management_root/yanxu-management-publication-store.run/data"
management_cp="$management_classes:$management_app/lib/h2.jar:$management_app/lib/pdfbox-app-3.0.8.jar:$management_app/lib/ip2region-3.3.7.jar"
"$management_java" -jar "$management_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$management_cp" -d "$management_root/test-out" \
  "$management_app/scripts/ManagementGroupPublicationTest.java" "$management_app/scripts/ManagementPublicationFixture.java" \
  "$management_app/scripts/ManagementPublicationStoreTest.java" "$management_app/scripts/ManagementGroupHttpBoundaryTest.java" \
  "$management_app/scripts/OrganizationAccountImportSourceTest.java" "$management_app/scripts/ManagementSupplementSourceTest.java"
management_test_cp="$management_root/test-out:$management_cp"
export JAVA_TOOL_OPTIONS='' JDK_JAVA_OPTIONS='' _JAVA_OPTIONS='' YANXU_LOGIN_MAIL_TRANSPORT=disabled
"$management_java" -Dlogin.email.mode=legacy -cp "$management_test_cp" com.training.ManagementGroupPublicationTest "$management_root/main-data" "$management_root/main-sources" main
"$management_java" -Dlogin.email.mode=legacy -cp "$management_test_cp" com.training.ManagementGroupPublicationTest "$management_root/actor-data" "$management_root/actor-sources" affected-actor
"$management_java" -Dlogin.email.mode=legacy -cp "$management_test_cp" com.training.ManagementPublicationStoreTest "$management_root/yanxu-management-publication-store.run"
"$management_java" -Dlogin.email.mode=legacy -cp "$management_test_cp" com.training.ManagementGroupHttpBoundaryTest "$management_root/boundary-data"
"$management_java" -Dlogin.email.mode=legacy -cp "$management_test_cp" com.training.ManagementGroupPublicationTest "$management_root/prepared-data" "$management_root/prepared-sources" prepare-http
"$management_python" "$management_app/scripts/ManagementGroupHttpFlow.py" --java "$management_java" --classes "$management_classes" \
  --fixture "$management_root/prepared-sources/management-http-fixture.json" --artifacts "$management_root/http-evidence"
printf 'Synthetic management group evidence: %s\n' "$management_root"
