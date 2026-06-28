# Model assets drop zone

Put exported model files here. They ship inside the APK; `AssetModels` copies them into `filesDir` for ExecuTorch `Module.load()` / `LlmModule`.

## Quick stage (SmolVLM + QNN libs)

```bash
HANDOFF=~/Downloads/udit-full-handoff ./tools/stage_vlm_assets.sh
```

This copies the 3× SmolVLM `.pte`, tokenizer, QNN `.so` libs, and refreshes `android/libs/executorch-qnn.aar`.

## Expected filenames

| File | Used by | Notes |
| --- | --- | --- |
| `vlm_encoder.pte` | `LlmModuleSceneDescriber` | SmolVLM vision encoder (QNN) |
| `vlm_text_embedding.pte` | `LlmModuleSceneDescriber` | Token embedding PTE |
| `vlm_decoder.pte` | `LlmModuleSceneDescriber` | Hybrid LLama decoder PTE |
| `tokenizer/tokenizer.json` (+ config, chat template) | `LlmModuleSceneDescriber` | HuggingFace tokenizer bundle |
| `classifier.pte` | `ClassifierSceneDescriber` | Places365 CPU hedge (live today) |
| `labels.txt` + `classifier_kind.txt` | Classifier | Scene label map |

**Engine selection** (`SceneDescribers.create()`):

1. All three VLM `.pte` + tokenizer → **VLM** (`LlmModuleSceneDescriber`)
2. Else `classifier.pte` + labels → **SCENE** (Places365)
3. Else → **MOCK**

## QNN native libs (not in assets)

Must also be present under `app/src/main/jniLibs/arm64-v8a/` (gitignored):

- `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`
- `libQnnHtpV79Skel.so` — **must match QAIRT version of host libs** (see `ml/teamb/RUNTIME_NEEDED.md`)

The stage script copies these from the handoff. Host libs are **2.46.0**; if your skel is **2.47.0**, NPU init fails until Jainil sends the matching skel.

(This `.md` file is ignored at runtime.)
