#!/usr/bin/env bash
# Test the supplied product build without adding test classes to it.
# Each suite owns a separate new synthetic database; no application data input.
set -euo pipefail
cd "$(dirname "$0")/.."
extension_java="${TRAINING_CHECK_JAVA:-java}"
extension_classes=""
while (($#)); do
  case "$1" in
    --java) extension_java="$2"; shift 2 ;;
    --classes) extension_classes="$2"; shift 2 ;;
    *) echo 'Usage: check_release_extensions.sh [--java /path/to/java] --classes /path/to/product/out' >&2; exit 2 ;;
  esac
done
[[ -n "$extension_classes" && -f "$extension_classes/com/training/Db.class" ]] || exit 2
extension_classes="$(cd "$extension_classes" && pwd -P)"
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
extension_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-release-extensions.XXXXXX")"
mkdir "$extension_root/test-out" "$extension_root/bridge-data" "$extension_root/reference-data" "$extension_root/photo-data" "$extension_root/photo-qa"
extension_cp="$extension_classes:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar"
"$extension_java" -jar lib/ecj.jar -17 -encoding UTF-8 -nowarn \
  -cp "$extension_cp" -d "$extension_root/test-out" \
  scripts/M03ReadAccessBridgeTest.java scripts/M05FormalWorkflowTest.java scripts/M05CasesTest.java scripts/M05ReferencesTest.java \
  scripts/M08DeliverySourceTest.java scripts/M08FormalTest.java scripts/M08PhotosTest.java
extension_cp="$extension_root/test-out:$extension_cp"
"$extension_java" -Xmx384m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$extension_cp" com.training.M03ReadAccessBridgeTest "$extension_root/bridge-data" | tee "$extension_root/bridge.log"
"$extension_java" -Xmx384m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$extension_cp" com.training.M05ReferencesTest "$extension_root/reference-data" guarded | tee "$extension_root/references.log"
"$extension_java" -Xmx384m -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$extension_cp" com.training.M08PhotosTest "$extension_root/photo-data" "$extension_root/photo-qa" | tee "$extension_root/photos.log"
printf 'Synthetic extension evidence: %s\n' "$extension_root"
