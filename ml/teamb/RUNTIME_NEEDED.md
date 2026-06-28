# SmolVLM on-device runtime — status (Team B)

## ✅ Received (udit-full-handoff) and verified on the S25 (SM-S938U1 / SM8750)

The full handoff contains everything we previously lacked, all arch-verified:

- `bin/qnn_multimodal_runner` (aarch64) — the 3-PTE SmolVLM runner (the path we need)
- `bin/qnn_llama_runner` (aarch64) — bonus
- `jniLibs/arm64-v8a/`: `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`,
  `libqnn_executorch_backend.so`
- `hexagon-v79/unsigned/libQnnHtpV79Skel.so` (Hexagon DSP6 / v79)
- `executorch-qnn.aar` — `libexecutorch.so` + QNN backend + `LlmModule` classes (enables in-app QNN)
- `assets/{vlm_encoder,vlm_text_embedding,vlm_decoder}.pte` + `assets/tokenizer/`

Validated up to device init: runner runs, tokenizer loads, **`-decoder_model_version smolvlm`** is
the correct value (the `kSmolvlm` path), and QNN reaches device creation.

## ❌ ONE blocker — QNN version mismatch (one file from Jainil)

The Hexagon skel is a different QAIRT version than the host libs:

| Lib | Version |
| --- | --- |
| `libQnnHtp.so` | **2.46.0**.260424121129 |
| `libQnnHtpV79Stub.so` | **2.46.0**.260424121129 |
| `libQnnHtpV79Skel.so` (included) | **2.47.0**.260601114230 |

Device init fails with:

```
QnnDsp <E> Skel lib id mismatch: expected (v2.46.0.260424121129), detected (v2.47.0.260601114230)
QnnDsp <E> Failed to load skel, error: 1008
```

### Fix (pick one; option A is easiest)

- **A.** Provide `libQnnHtpV79Skel.so` from **QAIRT 2.46.0** (same source as the host libs /
  `aura_collaterals`). Drop it in `hexagon-v79/unsigned/`. Nothing else changes.
- **B.** Provide **2.47.0** versions of `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`
  (and matching `libqnn_executorch_backend.so`) so the whole stack is 2.47.0 — only do this if the
  `.pte` were actually exported against 2.47.0.

Please also confirm which QAIRT version the three `.pte` were exported with, so we match A vs B.

## Then (validation, ~minutes)

```bash
HANDOFF=~/Downloads/udit-full-handoff \
IMAGE=/sdcard/DCIM/Camera/<a-photo>.jpg \
tools/run_smolvlm_device.sh
```

This stages everything to `/data/local/tmp/echowalk` and runs one inference over adb, proving the
NPU path before we wire JNI / the AAR into the app.
