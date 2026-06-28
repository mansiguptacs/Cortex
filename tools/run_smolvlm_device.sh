#!/usr/bin/env bash
#
# Phase-1 validation: run the 3-part SmolVLM QNN export on the S25 (SM8750) via adb shell,
# decoupled from the Android app. Proves the model works on the NPU before we wire it into the app.
#
# Uses the FULL handoff from Jainil (udit-full-handoff), which contains everything:
#   bin/qnn_multimodal_runner            (aarch64 runner for the 3-PTE SmolVLM)
#   jniLibs/arm64-v8a/*.so               (QNN host libs + libqnn_executorch_backend.so)
#   hexagon-v79/unsigned/libQnnHtpV79Skel.so  (Hexagon DSP skel for v79 / SM8750)
#   assets/{vlm_encoder,vlm_text_embedding,vlm_decoder}.pte + assets/tokenizer/
#
# Uses Jainil's handoff for models/runner/ExecuTorch backend, and qnn-runtime-2.46-v79 for
# version-matched QNN SDK libs + Hexagon skel (v2.46.0).
#
# Usage:
#   HANDOFF=~/Downloads/udit-full-handoff \
#   QNN_RUNTIME=~/Downloads/qnn-runtime-2.46-v79 \
#   IMAGE=/sdcard/DCIM/Camera/foo.jpg \
#   ./tools/run_smolvlm_device.sh
#
set -euo pipefail

HANDOFF="${HANDOFF:-$HOME/Downloads/udit-full-handoff}"
QNN_RUNTIME="${QNN_RUNTIME:-$HOME/Downloads/qnn-runtime-2.46-v79}"
DEVICE_DIR="/data/local/tmp/echowalk"
PROMPT="${PROMPT:-Describe this indoor scene clearly for a blind person. Mention layout, major objects, and anything in the walking path. Keep it to 2-3 sentences.}"
SEQ_LEN="${SEQ_LEN:-256}"
# IMAGE: a device-side path (e.g. /sdcard/DCIM/Camera/x.jpg) OR a host path to push. If a device
# path, it is copied into DEVICE_DIR; if a host file, it is pushed.
IMAGE="${IMAGE:-}"
adb="${ADB:-adb}"

[ -d "$HANDOFF" ] || { echo "ERROR: HANDOFF not found: $HANDOFF"; exit 1; }
[ -d "$QNN_RUNTIME" ] || { echo "ERROR: QNN_RUNTIME not found: $QNN_RUNTIME"; exit 1; }
"$adb" get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the S25 (SM8750) and unlock."; exit 1; }

echo ">> staging to $DEVICE_DIR"
"$adb" shell "mkdir -p $DEVICE_DIR/lib $DEVICE_DIR/tokenizer"

echo ">> pushing QNN 2.46 libs + skel + ExecuTorch backend + runner"
"$adb" push "$QNN_RUNTIME/jniLibs/arm64-v8a/." "$DEVICE_DIR/lib/" >/dev/null
"$adb" push "$QNN_RUNTIME/hexagon-v79/unsigned/libQnnHtpV79Skel.so" "$DEVICE_DIR/lib/" >/dev/null
"$adb" push "$HANDOFF/jniLibs/arm64-v8a/libqnn_executorch_backend.so" "$DEVICE_DIR/lib/" >/dev/null
"$adb" push "$HANDOFF/bin/qnn_multimodal_runner" "$DEVICE_DIR/qnn_multimodal_runner" >/dev/null
"$adb" shell "chmod 755 $DEVICE_DIR/qnn_multimodal_runner"

echo ">> pushing models + tokenizer (~570MB)"
for f in vlm_encoder.pte vlm_text_embedding.pte vlm_decoder.pte; do
  "$adb" push "$HANDOFF/assets/$f" "$DEVICE_DIR/$f" >/dev/null
done
"$adb" push "$HANDOFF/assets/tokenizer/." "$DEVICE_DIR/tokenizer/" >/dev/null

# Resolve the test image.
IMG_ARG=""
if [ -n "$IMAGE" ]; then
  if [ -f "$IMAGE" ]; then
    "$adb" push "$IMAGE" "$DEVICE_DIR/test.jpg" >/dev/null
  else
    "$adb" shell "cp '$IMAGE' $DEVICE_DIR/test.jpg"
  fi
  IMG_ARG="-image_path $DEVICE_DIR/test.jpg"
else
  echo "NOTE: set IMAGE=<device-or-host-path> to run inference; staging only."
fi

echo ">> running qnn_multimodal_runner (decoder_model_version=smolvlm, eval_mode=1)"
"$adb" shell "cd $DEVICE_DIR && \
  LD_LIBRARY_PATH=$DEVICE_DIR/lib \
  ADSP_LIBRARY_PATH=$DEVICE_DIR/lib \
  ./qnn_multimodal_runner \
    -decoder_model_version smolvlm \
    -encoder_path $DEVICE_DIR/vlm_encoder.pte \
    -tok_embedding_path $DEVICE_DIR/vlm_text_embedding.pte \
    -decoder_path $DEVICE_DIR/vlm_decoder.pte \
    -tokenizer_path $DEVICE_DIR/tokenizer/tokenizer.json \
    -eval_mode 1 -seq_len $SEQ_LEN \
    $IMG_ARG \
    -prompt \"$PROMPT\" \
    -output_path $DEVICE_DIR/out.txt 2>&1 | tail -60; \
  echo '===== OUT.TXT ====='; cat $DEVICE_DIR/out.txt 2>/dev/null"
