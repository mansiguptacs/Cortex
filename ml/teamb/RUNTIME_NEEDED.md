# SmolVLM on-device runtime — what's still needed (Team B → Jainil)

The handoff zip (`vlm-handoff-*.zip`) contains **models + tokenizer only**. To execute the 3-part
QNN SmolVLM on the S25, we also need the **QNN Android runtime** — which only the ExecuTorch+QNN
build host (the one that produced the `.pte`) can provide. The public `executorch-android:0.6.0`
AAR ships **CPU only** (`libexecutorch.so`); it has no QNN backend, so a QNN-delegated `.pte`
won't even `load()` without the libs below.

## Please provide (drop into `~/Downloads/qnn-android-runtime/`)

1. **`qnn_llama_runner`** (aarch64-android), built from
   `executorch/examples/qualcomm/oss_scripts/llama` — **or** an `executorch.aar` built with
   `-DEXECUTORCH_BUILD_QNN=ON` (so it bundles `LlmModule` + the QNN backend).
2. **`libqnn_executorch_backend.so`** from that same ExecuTorch QNN build.
3. **QNN SDK `.so` libs**, version-matched to the export:
   - `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`  (from `$QNN_SDK_ROOT/lib/aarch64-android/`)
   - `libQnnHtpV79Skel.so`  (from `$QNN_SDK_ROOT/lib/hexagon-v79/unsigned/`)
4. **QNN SDK / QAIRT version** used (e.g. 2.37.x) — must match the runtime.
5. The **exact runner command** you validated with, plus confirmation you got a sane description,
   and the **real image size + normalization** (IO_SPEC still lists these as TODO).

## Then (validation, ~minutes)

```bash
LIBS_DIR=~/Downloads/qnn-android-runtime \
IMAGE=~/Downloads/test_room.jpg \
tools/run_smolvlm_device.sh
```

This pushes models + tokenizer + runner + libs to `/data/local/tmp/echowalk` and runs one
inference over adb — proving the NPU path before we wire JNI into the app.

## Why not CPU?

These `.pte` are QNN/HTP-delegated; there is no CPU fallback for them. A CPU demo needs a
separate XNNPACK export (see `export_classifier_xnnpack.py` / the classifier hedge).
