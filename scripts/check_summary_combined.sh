#!/usr/bin/env bash
# Real authorization + independent synthetic stores; the product classes are read-only.
set -euo pipefail
cd "$(dirname "$0")/.."
combined_java="${TRAINING_CHECK_JAVA:-java}"
combined_classes=""
while (($#)); do
  case "$1" in
    --java) combined_java="$2"; shift 2 ;;
    --classes) combined_classes="$2"; shift 2 ;;
    *) echo 'Usage: check_summary_combined.sh [--java /path/to/java] --classes /path/to/product/out' >&2; exit 2 ;;
  esac
done
[[ -f "$combined_classes/com/training/TrainingSummariesCombinedReview.class" ]] || exit 2
combined_classes="$(cd "$combined_classes" && pwd -P)"
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
combined_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-summary-combined.XXXXXX")"
mkdir "$combined_root/test-out" "$combined_root/data" "$combined_root/formal-data" "$combined_root/qa" "$combined_root/formal-qa"
combined_baseline="scripts/fixtures/m08-ordinary-workflow-v1"
sed -e 's/TrainingSummariesWorkflow/TrainingSummariesPhotoBaselineWorkflow/g' -e 's/TrainingSummariesWord/TrainingSummariesPhotoBaselineWord/g' "$combined_baseline/TrainingSummariesWorkflow.java.fixture" > "$combined_root/TrainingSummariesPhotoBaselineWorkflow.java"
sed 's/TrainingSummariesWord/TrainingSummariesPhotoBaselineWord/g' "$combined_baseline/TrainingSummariesWord.java.fixture" > "$combined_root/TrainingSummariesPhotoBaselineWord.java"
combined_cp="$combined_classes:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar"
"$combined_java" -jar lib/ecj.jar -17 -encoding UTF-8 -nowarn -cp "$combined_cp" -d "$combined_root/test-out" \
  "$combined_root/TrainingSummariesPhotoBaselineWorkflow.java" "$combined_root/TrainingSummariesPhotoBaselineWord.java" \
  scripts/M08DeliverySourceTest.java scripts/M08FormalTest.java scripts/M08PhotosTest.java scripts/M08CombinedTest.java
combined_cp="$combined_root/test-out:$combined_cp"
"$combined_java" -Xmx256m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$combined_cp" com.training.M08CombinedTest "$combined_root/data" "$combined_root/qa" | tee "$combined_root/combined.log"
"$combined_java" -Xmx256m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$combined_cp" com.training.M08FormalTest "$combined_root/formal-data" "$combined_root/formal-qa" | tee "$combined_root/formal.log"
printf 'Synthetic summary evidence: %s\n' "$combined_root"
