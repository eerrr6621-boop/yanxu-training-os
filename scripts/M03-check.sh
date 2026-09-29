#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
m03_java="${M03_JAVA:-java}"
if [[ ! -x "$m03_java" ]]; then m03_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"; fi
m03_out="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-m03-check.XXXXXX")"
"$m03_java" -jar lib/ecj.jar -17 -encoding UTF-8 -d "$m03_out" \
  src/com/training/ApprovalWorkflow.java src/com/training/ApprovalWorkflowSnapshots.java src/com/training/Json.java \
  scripts/M03ApprovalWorkflowTest.java scripts/M03CombinedApprovalTest.java scripts/M03ApprovalSnapshotsTest.java
"$m03_java" -cp "$m03_out" com.training.M03ApprovalWorkflowTest
"$m03_java" -cp "$m03_out" com.training.M03CombinedApprovalTest
"$m03_java" -cp "$m03_out" com.training.M03ApprovalSnapshotsTest
printf 'M03 temporary test classes: %s\n' "$m03_out"
