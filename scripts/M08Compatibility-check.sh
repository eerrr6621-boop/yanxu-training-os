#!/usr/bin/env bash
set -euo pipefail
m08_script="$(cd "$(dirname "$0")" && pwd)"
m08_app="${M08_FORMAL_APP_ROOT:-$(cd "$m08_script/.." && pwd)}"
m08_source="${M08_FORMAL_SOURCE_ROOT:-$m08_script}"
if [[ ! -f "$m08_source/TrainingSummariesIntegration.java" ]]; then m08_source="$m08_app/src/com/training"; fi
m08_java="${M08_JAVA:-java}"
m08_python="${M08_PYTHON:-python3}"
m08_tmp="$(mktemp -d "${TMPDIR:-/tmp}/m08-formal-compat.XXXXXXXX")"
trap 'rm -rf -- "$m08_tmp"' EXIT
mkdir "$m08_tmp/classes" "$m08_tmp/drafts" "$m08_tmp/sources" "$m08_tmp/compare" "$m08_tmp/legacy"
m08_cp="$m08_app/lib/h2.jar:$m08_app/lib/pdfbox-app-3.0.8.jar:$m08_app/lib/ip2region-3.3.7.jar"
# Only the two retired disabled-formal assertions change: old malformed commands now fail strict DTO validation with 400.
"$m08_python" - "$m08_app/scripts/M08IntegrationTest.java" "$m08_tmp/M08IntegrationTest.java" <<'PY'
from pathlib import Path
import sys
text=Path(sys.argv[1]).read_text()
lines=text.splitlines(True);changed=0
for index,line in enumerate(lines):
    if 'for(String op:List.of("submit","review","export"))rejects(' in line or 'for(String operation:List.of("submit","review","export"))rejects(' in line:
        lines[index]=line.replace('rejects(409','rejects(400',1);changed+=1
assert changed==2, 'Old compatibility boundary changed; inspect before updating'
Path(sys.argv[2]).write_text(''.join(lines))
PY
m08_sources=()
for f in "$m08_app"/src/com/training/*.java; do
 case "${f##*/}" in TrainingSummariesIntegration.java|TrainingSummariesWorkflow.java|TrainingSummariesWord.java|TrainingSummariesFeedbackSource.java) continue;; esac
 m08_sources+=("$f")
done
"$m08_java" -jar "$m08_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m08_cp" -d "$m08_tmp/classes" "${m08_sources[@]}" "$m08_source/TrainingSummariesIntegration.java" "$m08_source/TrainingSummariesWorkflow.java" "$m08_source/TrainingSummariesWord.java" "$m08_source/TrainingSummariesFeedbackSource.java" "$m08_tmp/M08IntegrationTest.java" "$m08_app/scripts/M08DeliverySourceTest.java" "$m08_app/scripts/M08ComparisonTest.java"
"$m08_java" -Xmx256m -cp "$m08_tmp/classes:$m08_cp" com.training.M08IntegrationTest "$m08_tmp/drafts"
"$m08_java" -Xmx256m -cp "$m08_tmp/classes:$m08_cp" com.training.M08DeliverySourceTest "$m08_tmp/sources" "$m08_tmp/legacy"
"$m08_java" -Xmx256m -cp "$m08_tmp/classes:$m08_cp" com.training.M08ComparisonTest "$m08_tmp/compare"
