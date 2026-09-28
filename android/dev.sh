#!/bin/bash
# ./dev.sh run — 빌드 → 설치 → 실행   (build · run · log)
#
# 환경변수
#   JAVA_HOME       JDK 21 (없으면 시스템 기본 java 사용)
#   ANDROID_HOME    Android SDK (기본 $HOME/Library/Android/sdk)
#   ANDROID_SERIAL  기기가 여러 대일 때 대상 시리얼 또는 무선 adb 주소(host:port)
#   PINBACK_ADB_CONNECT  무선 adb 로 붙을 주소(host:port). 지정하면 먼저 adb connect 한다
set -e
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
D=$(cd "$(dirname "$0")" && pwd)

resolve_device() {
  if [ -n "$PINBACK_ADB_CONNECT" ]; then
    adb connect "$PINBACK_ADB_CONNECT" >/dev/null || true
    export ANDROID_SERIAL="${ANDROID_SERIAL:-$PINBACK_ADB_CONNECT}"
  fi
  if [ -z "$ANDROID_SERIAL" ]; then
    local n
    n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
    if [ "$n" = "0" ]; then echo "연결된 기기가 없어요. USB 또는 무선 adb 로 연결하세요." >&2; exit 1; fi
    if [ "$n" != "1" ]; then echo "기기가 ${n}대예요. ANDROID_SERIAL 로 하나를 고르세요." >&2; adb devices >&2; exit 1; fi
  fi
}

case "${1:-run}" in
  build) "$D/gradlew" -p "$D" -q assembleDebug ;;
  run)
    "$D/gradlew" -p "$D" -q assembleDebug
    resolve_device
    adb install -r "$D/app/build/outputs/apk/debug/app-debug.apk"
    adb shell am start -n kr.joonlab.pinback/.MainActivity ;;
  log) resolve_device; adb logcat --pid="$(adb shell pidof kr.joonlab.pinback)" ;;
  *) echo "사용법: $0 [build|run|log]" >&2; exit 2 ;;
esac
