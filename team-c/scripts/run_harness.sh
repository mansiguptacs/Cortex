#!/usr/bin/env bash
#
# Build (if needed), install, and launch the Team C standalone harness on a connected device.
# No model file / ExecuTorch required — uses the CPU fallback embedder.
#
# Usage:  ./scripts/run_harness.sh          # install + launch
#         ./scripts/run_harness.sh --build   # rebuild the APK first
#         ./scripts/run_harness.sh --logs     # install, launch, then stream filtered logcat
#
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$PROJECT_DIR/harness/build/outputs/apk/debug/harness-debug.apk"
PKG="com.echowalk.harness"
ACTIVITY="$PKG/.HarnessActivity"

if [[ "${1:-}" == "--build" ]]; then
  echo ">> Building APK..."
  (cd "$PROJECT_DIR" && ./gradlew :harness:assembleDebug)
fi

if [[ ! -f "$APK" ]]; then
  echo "APK not found at $APK — run with --build first." >&2
  exit 1
fi

echo ">> Waiting for an authorized device..."
"$ADB" wait-for-device
state="$("$ADB" get-state 2>/dev/null || true)"
if [[ "$state" != "device" ]]; then
  echo "Device not authorized (state=$state). Unlock the phone and tap 'Allow USB debugging'." >&2
  "$ADB" devices -l
  exit 1
fi

echo ">> Installing $APK"
"$ADB" install -r -g "$APK"

echo ">> Launching $ACTIVITY"
"$ADB" shell am start -n "$ACTIVITY"

echo ">> Done. App is on screen."

if [[ "${1:-}" == "--logs" || "${2:-}" == "--logs" ]]; then
  echo ">> Streaming harness logs (Ctrl-C to stop)..."
  "$ADB" logcat -c
  "$ADB" logcat -s Harness:I
fi
