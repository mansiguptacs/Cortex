#!/usr/bin/env bash
#
# Phase-1 validation: run the 3-part SmolVLM QNN export on the S25 via adb shell,
# decoupled from the Android app. Proves the model works on the NPU before we wire JNI.
#
# PREREQUISITES (must be supplied by Jainil's ExecuTorch+QNN build host — NOT in the model handoff):
#   - qnn_llama_runner            (aarch64-android binary, from executorch examples/qualcomm/oss_scripts/llama)
#   - libqnn_executorch_backend.so (ExecuTorch built with -DEXECUTORCH_BUILD_QNN=ON)
#   - QNN SDK libs (version-matched): libQnnHtp.so, libQnnHtpV79Stub.so, libQnnSystem.so,
#                                     libQnnHtpV79Skel.so  (the skel is in hexagon-v79/unsigned/)
#   - libexecutorch.so / runner deps as needed by the build
# Put the runner + all .so files in:  $LIBS_DIR  (see below)
#
# Usage:
#   LIBS_DIR=~/Downloads/qnn-android-runtime ./tools/run_smolvlm_device.sh
#
set -euo pipefail

# --- inputs -----------------------------------------------------------------
MODELS_DIR="${MODELS_DIR:-$HOME/Downloads/vlm-handoff-20260627T225746Z}"
LIBS_DIR="${LIBS_DIR:-$HOME/Downloads/qnn-android-runtime}"   # runner + .so libs go here
DEVICE_DIR="/data/local/tmp/echowalk"
PROMPT="${PROMPT:-Describe this indoor scene clearly for a blind person. Mention layout, major objects, and anything in the walking path. Keep it to 2-3 sentences.}"
IMAGE="${IMAGE:-}"   # path to a test image (jpg/png or preprocessed raw, per runner expectations)

adb="${ADB:-adb}"

# --- checks -----------------------------------------------------------------
[ -d "$MODELS_DIR" ] || { echo "ERROR: MODELS_DIR not found: $MODELS_DIR"; exit 1; }
if [ ! -d "$LIBS_DIR" ]; then
  echo "ERROR: LIBS_DIR not found: $LIBS_DIR"
  echo "       This must contain qnn_llama_runner + libqnn_executorch_backend.so + QNN SDK .so libs."
  echo "       Ask Jainil to build/share them (see header of this script)."
  exit 1
fi
"$adb" get-state >/dev/null 2>&1 || { echo "ERROR: no adb device. Plug in the S25 and unlock."; exit 1; }

# --- push -------------------------------------------------------------------
echo ">> creating $DEVICE_DIR"
"$adb" shell "mkdir -p $DEVICE_DIR/lib $DEVICE_DIR/tokenizer"

echo ">> pushing models (~580MB, takes a bit)"
for f in vlm_encoder.pte vlm_text_embedding.pte vlm_decoder.pte; do
  "$adb" push "$MODELS_DIR/$f" "$DEVICE_DIR/$f"
done
"$adb" push "$MODELS_DIR/tokenizer/." "$DEVICE_DIR/tokenizer/"

echo ">> pushing runner + libs"
"$adb" push "$LIBS_DIR/." "$DEVICE_DIR/lib/"
"$adb" shell "cp $DEVICE_DIR/lib/qnn_llama_runner $DEVICE_DIR/qnn_llama_runner 2>/dev/null || true; chmod 755 $DEVICE_DIR/qnn_llama_runner 2>/dev/null || true"

[ -n "$IMAGE" ] && "$adb" push "$IMAGE" "$DEVICE_DIR/test_image" || echo "NOTE: set IMAGE=<path> to run inference; otherwise this only stages files."

# --- run --------------------------------------------------------------------
# NOTE: exact flags depend on Jainil's validated command. Confirm against his run.
# Reference (oss_scripts/llama multimodal): --decoder_model smolvlm_500m_instruct,
#   --model_mode hybrid --prefill_ar_len 16 --max_new_tokens 128 --seq_len 1024
echo ">> running (CONFIRM FLAGS with Jainil's validated command)"
"$adb" shell "cd $DEVICE_DIR && \
  LD_LIBRARY_PATH=$DEVICE_DIR/lib \
  ADSP_LIBRARY_PATH=$DEVICE_DIR/lib \
  ./qnn_llama_runner \
    --decoder_model smolvlm_500m_instruct \
    --tokenizer_path $DEVICE_DIR/tokenizer/tokenizer.json \
    --decoder_path $DEVICE_DIR/vlm_decoder.pte \
    --encoder_path $DEVICE_DIR/vlm_encoder.pte \
    --token_embedding_path $DEVICE_DIR/vlm_text_embedding.pte \
    --model_mode hybrid --prefill_ar_len 16 \
    --max_new_tokens 128 --seq_len 1024 \
    ${IMAGE:+--image_path $DEVICE_DIR/test_image} \
    --prompt \"$PROMPT\" "
