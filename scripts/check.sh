#!/usr/bin/env bash
# Compile and test with three independent, throwaway databases. No production input.
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check_frontend.cjs
node scripts/test_local_esm.cjs
node scripts/test_frontend.cjs
node scripts/test_regions.cjs
node scripts/test_login_book.cjs
node scripts/test_book_geometry.mjs
node scripts/test_book_binding.cjs
node scripts/test_book_headlines.cjs
node scripts/check_business_art.cjs
node scripts/test_r7.cjs
node scripts/test_environment.cjs
check_root="$(mktemp -d "${TMPDIR:-/tmp}/yanxu-check.XXXXXX")"
check_pid=""
check_java="${TRAINING_CHECK_JAVA:-java}"
cleanup() {
  if [[ -n "$check_pid" ]] && kill -0 "$check_pid" 2>/dev/null; then
    kill "$check_pid" 2>/dev/null || true
    wait "$check_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
mkdir -p "$check_root/out"
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 \
  -cp 'lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar' -d "$check_root/out" src/com/training/*.java
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d "$check_root/out" scripts/DispatchPreferenceTest.java
"$check_java" -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.DispatchPreferenceTest
"$check_java" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "$check_root/out:lib/ip2region-3.3.7.jar" -d "$check_root/out" scripts/WeatherTest.java
"$check_java" -cp "$check_root/out:lib/ip2region-3.3.7.jar" com.training.WeatherTest
for check_file in web/app.js web/materials.js test.js test_integrity.js test_faculty.js; do
  node --check "$check_file"
done
run_suite() {
  local port="$1" suite="$2" ready=0
  # Refuse occupied ports instead of accidentally testing someone else's instance.
  node -e 'const n=require("node:net"),s=n.createServer();s.on("error",()=>{console.error("Test port already in use");process.exit(1)});s.listen(Number(process.argv[1]),"127.0.0.1",()=>s.close())' "$port"
  mkdir -p "$check_root/${suite%.js}"
  "$check_java" -Dfile.encoding=UTF-8 -Dbootstrap.demo=true -Dbind.address=127.0.0.1 \
    -Ddata.dir="$check_root/${suite%.js}" \
    -cp "$check_root/out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar:lib/ip2region-3.3.7.jar" com.training.Main "$port" \
    >"$check_root/${suite%.js}.log" 2>&1 &
  check_pid=$!
  for ((attempt = 0; attempt < 80; attempt++)); do
    if ! kill -0 "$check_pid" 2>/dev/null; then break; fi
    if curl --fail --silent "http://127.0.0.1:$port/" >/dev/null; then ready=1; break; fi
    sleep 0.25
  done
  if [[ "$ready" != 1 ]]; then
    tail -n 40 "$check_root/${suite%.js}.log"
    return 1
  fi
  TRAINING_API_BASE="http://127.0.0.1:$port/api" node "$suite"
  kill "$check_pid"
  wait "$check_pid" 2>/dev/null || true
  check_pid=""
}
run_suite 18081 test.js
run_suite 18082 test_integrity.js
run_suite 18084 test_faculty.js
printf 'All regression suites passed. Isolated logs: %s\n' "$check_root"
