#!/usr/bin/env bash
set -euo pipefail
m08_script="$(cd "$(dirname "$0")" && pwd)"
m08_app="${M08_FORMAL_APP_ROOT:-$(cd "$m08_script/.." && pwd)}"
m08_m07="${M08_M07_CANDIDATE:-$m08_app/src/com/training}"
m08_source="${M08_FORMAL_SOURCE_ROOT:-$m08_script}"
if [[ ! -f "$m08_source/TrainingSummariesIntegration.java" ]]; then m08_source="$m08_app/src/com/training"; fi
m08_java="${M08_JAVA:-java}"
m08_tmp="$(mktemp -d "${TMPDIR:-/tmp}/m08-m07-joint.XXXXXXXX")"
trap 'rm -rf -- "$m08_tmp"' EXIT
mkdir "$m08_tmp/classes" "$m08_tmp/data"
m08_cp="$m08_app/lib/h2.jar:$m08_app/lib/pdfbox-app-3.0.8.jar:$m08_app/lib/ip2region-3.3.7.jar"
m08_sources=()
for f in "$m08_app"/src/com/training/*.java; do
 case "${f##*/}" in TrainingSummariesIntegration.java|TrainingSummariesWorkflow.java|TrainingSummariesWord.java|TrainingSummariesFeedbackSource.java|SurveySummaryImportsFormal.java|SurveySummaryImportsPolicy.java|SurveySummaryImportsResponses.java|SurveySummaryImportsIntegration.java) continue;; esac
 m08_sources+=("$f")
done
"$m08_java" -jar "$m08_app/lib/ecj.jar" -17 -encoding UTF-8 -nowarn -cp "$m08_cp" -d "$m08_tmp/classes" "${m08_sources[@]}" "$m08_source/TrainingSummariesIntegration.java" "$m08_source/TrainingSummariesWorkflow.java" "$m08_source/TrainingSummariesWord.java" "$m08_source/TrainingSummariesFeedbackSource.java" "$m08_m07/SurveySummaryImportsFormal.java" "$m08_m07/SurveySummaryImportsPolicy.java" "$m08_m07/SurveySummaryImportsResponses.java" "$m08_m07/SurveySummaryImportsIntegration.java" "$m08_app/scripts/M08DeliverySourceTest.java" "$m08_script/M08FormalTest.java" "$m08_script/M08ReviewedSurveyTest.java"
"$m08_java" -Xmx256m -Dfile.encoding=UTF-8 -cp "$m08_tmp/classes:$m08_cp" com.training.M08ReviewedSurveyTest "$m08_tmp/data"
