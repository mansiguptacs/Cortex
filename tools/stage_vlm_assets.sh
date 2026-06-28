#!/usr/bin/env bash
#
# Copy SmolVLM model assets + QNN native libs into the Android app tree.
# Large files are gitignored; run once after downloading Jainil's handoff zips.
#
# Usage:
#   HANDOFF=~/Downloads/udit-full-handoff \
#   QNN_RUNTIME=~/Downloads/qnn-runtime-2.46-v79 \
#   ./tools/stage_vlm_assets.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HANDOFF="${HANDOFF:-$HOME/Downloads/udit-full-handoff}"
QNN_RUNTIME="${QNN_RUNTIME:-$HOME/Downloads/qnn-runtime-2.46-v79}"
ASSETS="$ROOT/android/app/src/main/assets"
JNILIBS="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
LIBS="$ROOT/android/libs"

[ -d "$HANDOFF" ] || { echo "ERROR: HANDOFF not found: $HANDOFF"; exit 1; }
[ -d "$QNN_RUNTIME" ] || { echo "ERROR: QNN_RUNTIME not found: $QNN_RUNTIME"; exit 1; }

echo ">> copying executorch-qnn.aar -> android/libs/"
mkdir -p "$LIBS"
cp "$HANDOFF/executorch-qnn.aar" "$LIBS/executorch-qnn.aar"

echo ">> copying 3× .pte + tokenizer -> android/app/src/main/assets/"
mkdir -p "$ASSETS/tokenizer"
for f in vlm_encoder.pte vlm_text_embedding.pte vlm_decoder.pte; do
  cp "$HANDOFF/assets/$f" "$ASSETS/$f"
  echo "   $f ($(du -h "$ASSETS/$f" | awk '{print $1}'))"
done
cp "$HANDOFF/assets/tokenizer/"* "$ASSETS/tokenizer/"

echo ">> copying QNN jniLibs (QAIRT 2.46.0 from QNN_RUNTIME + ExecuTorch backend from HANDOFF)"
mkdir -p "$JNILIBS"
cp "$QNN_RUNTIME/jniLibs/arm64-v8a/"*.so "$JNILIBS/"
cp "$QNN_RUNTIME/hexagon-v79/unsigned/libQnnHtpV79Skel.so" "$JNILIBS/"
# ExecuTorch QNN delegate — not in QAIRT zip; keep from aura handoff.
cp "$HANDOFF/jniLibs/arm64-v8a/libqnn_executorch_backend.so" "$JNILIBS/"
echo "   jniLibs: $(ls "$JNILIBS" | tr '\n' ' ')"

echo ""
echo "Done. Rebuild and install:"
echo "  cd android && ./gradlew :app:installDebug"
echo ""
echo "QNN host libs + skel: QAIRT 2.46.0 ($QNN_RUNTIME)"
echo "Models + runner validation: see tools/run_smolvlm_device.sh"
