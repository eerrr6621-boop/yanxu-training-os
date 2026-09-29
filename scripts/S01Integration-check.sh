#!/usr/bin/env bash
set -euo pipefail
s01_script_dir="$(cd "$(dirname "$0")" && pwd)"
s01_root="${S01_INTEGRATION_APP_ROOT:-$(cd "$s01_script_dir/.." && pwd)}"
s01_java="${S01_INTEGRATION_JAVA:-java}"
s01_adapter="${S01_INTEGRATION_ADAPTER_SOURCE:-$s01_script_dir/../src/com/training/NotificationChannelsIntegration.java}"
s01_tmp="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-s01-integration.XXXXXX")"
# This invocation owns only fresh compiled classes and synthetic H2 files here.
trap 'rm -rf "$s01_tmp"' EXIT
export YANXU_CITY_PLANNING_COLLECTION_FILE=""
mkdir -p "$s01_tmp/classes" "$s01_tmp/data" "$s01_tmp/src/com/training"
# Synthetic wiring only: copy the real workflow and replace its private outbox body.
# The test-only failpoint proves an exception after notification insertion rolls back
# the caller's workflow transaction, rather than committing inside the adapter.
awk '
  /^[[:space:]]*private static void outbox\(String key,long demand,Map<String,Object> payload\) throws Exception \{.*\}$/ {
    print "    private static void outbox(String key,long demand,Map<String,Object> payload) throws Exception {";
    print "        NotificationChannelsIntegration.appendOutboxInTransaction(key,demand,payload);";
    print "        if(Boolean.getBoolean(\"s01.test.failAfterAppend\")) throw new Api.ApiException(409,\"Synthetic S01 rollback failpoint\");";
    print "    }";
    changed++; next;
  }
  { print }
  END { if(changed!=1) { print "Workflow outbox shape changed; refusing unverified synthetic wiring" > "/dev/stderr"; exit 2; } }
' "$s01_root/src/com/training/WorkflowIntegration.java" > "$s01_tmp/src/com/training/WorkflowIntegration.java"
s01_classpath="$s01_root/lib/h2.jar:$s01_root/lib/pdfbox-app-3.0.8.jar:$s01_root/lib/ip2region-3.3.7.jar"
s01_sources=()
for s01_source in "$s01_root"/src/com/training/*.java; do
  case "$s01_source" in */WorkflowIntegration.java|*/NotificationChannelsIntegration.java) continue ;; esac
  s01_sources+=("$s01_source")
done
"$s01_java" -jar "$s01_root/lib/ecj.jar" -17 -encoding UTF-8 -nowarn \
  -cp "$s01_classpath" -d "$s01_tmp/classes" \
  "${s01_sources[@]}" "$s01_tmp/src/com/training/WorkflowIntegration.java" \
  "$s01_adapter" "$s01_script_dir/S01IntegrationTest.java"
"$s01_java" -cp "$s01_tmp/classes:$s01_classpath" com.training.S01IntegrationTest "$s01_tmp/data"
