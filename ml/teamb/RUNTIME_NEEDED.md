# SmolVLM on-device runtime — status (Team B)

## ✅ QNN version mismatch — FIXED (Jainil `qnn-runtime-2.46-v79`)

Use **`qnn-runtime-2.46-v79`** for QNN SDK libs + Hexagon skel (all **v2.46.0.260424121129**).

Keep from **`udit-full-handoff`**:

- `assets/{vlm_encoder,vlm_text_embedding,vlm_decoder}.pte` + `tokenizer/`
- `libqnn_executorch_backend.so` (ExecuTorch build — not in QAIRT zip)
- `executorch-qnn.aar`, `bin/qnn_multimodal_runner`

Stage into the app:

```bash
HANDOFF=~/Downloads/udit-full-handoff \
QNN_RUNTIME=~/Downloads/qnn-runtime-2.46-v79 \
./tools/stage_vlm_assets.sh
```

**Validated on S25 (SM-S938U1 / SM8750):** with 2.46.0 skel, QNN backend **initializes successfully**
(no more `Skel lib id mismatch ... error 1008`).

## ❌ ROOT CAUSE: `QnnContextCustomProtocol` version mismatch

### What we found

The PTEs and the runner binary/AAR were built against **different ExecuTorch commits**.

**Evidence:** every PTE load logs:
```
QnnContextCustomProtocol expected magic number: 0x5678abcd but get: 0x2000000
```

The runner falls back to a different deserialization path, which loads the QNN graphs but
**silently corrupts the I/O metadata** needed by `IOManager`.

### Crash behavior

| Scenario | Result |
|---|---|
| No image (`-prompt 'Hello'`) | ✅ io_memory OK, asserts "image required" |
| Empty image file (0 bytes) | ✅ io_memory OK, no output |
| Valid JPEG (any size: 4×4 to 4000×3000) | ❌ SIGSEGV at `creating io_memory` |

The crash is a **null pointer deref** in IOManager when it tries to allocate I/O buffers for
the multimodal (image) pipeline. Text-only path works because it skips image buffers.

### Both paths affected

1. **`qnn_multimodal_runner` (adb):** Segfault at `multimodal_runner.cpp:395`
2. **`LlmModule` (in-app):** `ExecuTorchInvalidArgumentException: Failed to load model runner`
   (error 0x12) — same underlying crash caught as an exception.

### What Jainil needs to do

**Option A (preferred):** Re-export the PTEs from the **same ExecuTorch commit** used to build
`executorch-qnn.aar` and `qnn_multimodal_runner`:

```bash
python examples/qualcomm/oss_scripts/llama/llama.py \
  -b build-android -s ${SERIAL} -m SM8750 \
  --decoder_model smolvlm_500m_instruct \
  --model_mode hybrid --prefill_ar_len 16 --max_seq_len 1024 \
  --prompt "Can you describe this image?" \
  --image_path "https://cdn.britannica.com/61/93061-050-99147DCE/Statue-of-Liberty-Island-New-York-Bay.jpg"
```

**Option B:** Rebuild `qnn_multimodal_runner` and `executorch-qnn.aar` from the **same commit**
that exported the PTEs.

**Key check:** after fix, the `QnnContextCustomProtocol expected magic number` message should
disappear or show `0x5678abcd`.

### Workaround in the app

`LlmModuleSceneDescriber` now has two strategies:
1. Try `LlmModule` (high-level multimodal runner)
2. Fall back to raw `Module.load()` per-PTE (bypasses broken C++ runner)
3. Fall back to Places365 classifier (always works)

The raw Module path loads each PTE individually and logs available methods, useful for debugging.

## Quick validation

```bash
QNN_RUNTIME=~/Downloads/qnn-runtime-2.46-v79 \
HANDOFF=~/Downloads/udit-full-handoff \
IMAGE=/sdcard/DCIM/Camera/<photo>.jpg \
tools/run_smolvlm_device.sh
```
