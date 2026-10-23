#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"

(cd "$ROOT/web" && npm run build:demo)
"$ROOT/gradlew" :demo:installDebug --console=plain

adb -s "$ANDROID_SERIAL" shell am force-stop me.xechoz.jsbridge.demo
adb -s "$ANDROID_SERIAL" shell am start -n me.xechoz.jsbridge.demo/.MainActivity
echo "launched me.xechoz.jsbridge.demo on $ANDROID_SERIAL"
