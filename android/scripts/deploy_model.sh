#!/usr/bin/env bash
#
# Deploy Team C's CLIP place-encoder .pte for local (on-device) testing with ADB only.
#
# Two destinations:
#   1) android/app/src/main/assets/place_encoder.pte  -> bundled into the APK at build time
#      (this is the name ExecuTorchModule/the app expect).
#   2) /data/local/tmp/place_encoder.pte on the device -> for quick adb-only validation /
#      qnn_executor_runner, WITHOUT rebuilding the app.
#
# Usage:
#   ./scripts/deploy_model.sh /path/to/your_quantized_model.pte
#   ./scripts/deploy_model.sh /path/to/model.pte --device-only   # skip copying into assets
#   ./scripts/deploy_model.sh /path/to/model.pte --assets-only    # skip adb push
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSET_NAME="place_encoder.pte"
ASSET_DIR="$REPO_ROOT/app/src/main/assets"
DEVICE_DIR="/data/local/tmp"

SRC="${1:-}"
MODE="${2:-both}"

if [[ -z "$SRC" ]]; then
  echo "ERROR: pass the path to your .pte file" >&2
  echo "  $0 /path/to/model.pte [--device-only|--assets-only]" >&2
  exit 1
fi
if [[ ! -f "$SRC" ]]; then
  echo "ERROR: file not found: $SRC" >&2
  exit 1
fi

echo "Source model : $SRC ($(du -h "$SRC" | cut -f1))"

# 1) Copy into app assets (unless device-only).
if [[ "$MODE" != "--device-only" ]]; then
  mkdir -p "$ASSET_DIR"
  cp -f "$SRC" "$ASSET_DIR/$ASSET_NAME"
  echo "Copied to    : $ASSET_DIR/$ASSET_NAME"
fi

# 2) Push to the device (unless assets-only).
if [[ "$MODE" != "--assets-only" ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "WARN: adb not on PATH; skipping device push." >&2
    exit 0
  fi
  N_DEVICES="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)"
  if [[ "$N_DEVICES" -eq 0 ]]; then
    echo "WARN: no authorized device. Connect the phone (USB debugging ON) and run:" >&2
    echo "  adb devices" >&2
    exit 0
  fi
  adb push "$SRC" "$DEVICE_DIR/$ASSET_NAME"
  echo "Pushed to    : device:$DEVICE_DIR/$ASSET_NAME"
  echo "On-device check:"
  adb shell ls -l "$DEVICE_DIR/$ASSET_NAME"
  adb shell md5sum "$DEVICE_DIR/$ASSET_NAME" || true
fi

echo "Done."
