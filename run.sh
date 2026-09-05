#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"
PORT=8080
DEMO_MODE=0
for arg in "$@"; do
  case "$arg" in
    --demo) DEMO_MODE=1 ;;
    ''|*[!0-9]*) ;;
    *) PORT="$arg" ;;
  esac
done

find_java() {
  local candidate
  for candidate in \
    "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
    "/opt/homebrew/opt/openjdk@21/bin/java" \
    "/opt/homebrew/opt/openjdk@17/bin/java" \
    "/usr/local/opt/openjdk@21/bin/java" \
    "/usr/local/opt/openjdk@17/bin/java" \
    "$(command -v java 2>/dev/null || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ] && "$candidate" -version >/dev/null 2>&1; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

JAVA_BIN="$(find_java || true)"
if [ -z "$JAVA_BIN" ]; then
  echo "未找到可用的 Java 17 或更高版本。"
  echo "请先安装 Java，再重新启动本系统。"
  exit 1
fi

NEEDS_COMPILE=0
if [ ! -f out/com/training/Main.class ]; then
  NEEDS_COMPILE=1
elif [ -n "$(find src -name '*.java' -newer out/com/training/Main.class -print -quit)" ]; then
  NEEDS_COMPILE=1
fi

if [ "$NEEDS_COMPILE" -eq 1 ]; then
  echo "正在更新本地程序…"
  mkdir -p out
  "$JAVA_BIN" -jar lib/ecj.jar -17 -encoding UTF-8 -cp "lib/h2.jar:lib/pdfbox-app-3.0.8.jar" -d out src/com/training/*.java
fi

echo "研序培训运营中心已启动：http://localhost:$PORT"
echo "关闭此窗口即可停止服务。"
JAVA_ARGS=(-Dfile.encoding=UTF-8)
if [ "$DEMO_MODE" -eq 1 ]; then
  DEMO_BIND_ADDRESS="${TRAINING_BIND_ADDRESS:-127.0.0.1}"
  JAVA_ARGS+=(-Dbootstrap.demo=true -Ddata.dir=demo-data "-Dbind.address=$DEMO_BIND_ADDRESS")
fi
exec "$JAVA_BIN" "${JAVA_ARGS[@]}" -cp "out:lib/h2.jar:lib/pdfbox-app-3.0.8.jar" com.training.Main "$PORT"
