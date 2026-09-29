#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
workflow_java="${WORKFLOW_JAVA:-java}"
if [[ ! -x "$workflow_java" ]]; then workflow_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"; fi
workflow_out="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-workflow-check.XXXXXX")"
mkdir "$workflow_out/classes" "$workflow_out/data"
"$workflow_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp 'lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar' -d "$workflow_out/classes" src/com/training/*.java scripts/IntegrationWorkflowTest.java
"$workflow_java" -cp "$workflow_out/classes:lib/*" com.training.IntegrationWorkflowTest "$workflow_out/data"
printf 'Isolated workflow test output: %s\n' "$workflow_out"
