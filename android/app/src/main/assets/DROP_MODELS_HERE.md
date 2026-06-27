# Model assets drop zone

Put exported `.pte` model files (and any tokenizer/label files) **in this folder**.
They get bundled into the APK and are reachable at runtime via `context.assets.open("<name>")`,
which `AssetModels.ensure(...)` stages into `filesDir` for ExecuTorch's `Module.load(path)`.

## Expected filenames (current code looks for these)

| File                | Used by                                   | Notes |
|---------------------|-------------------------------------------|-------|
| `vlm.pte`           | `SmolVlmSceneDescriber` (Team B)          | If present, the harness flips to engine `[VLM]`; otherwise it falls back to `MockSceneDescriber` (engine `[MOCK]`). |
| `labels.txt`        | classifier/detector decode (if used)      | One class name per line (ImageNet-1k or COCO-80), matching the model's output order. |
| `tokenizer.*`       | full SmolVLM decode (U-Step 5)            | Only needed for a real autoregressive VLM. |

> If your export produced **multiple** parts (e.g. SmolVLM's `encoder.pte` +
> `text_embedding.pte` + `decoder.pte`), drop them all here and tell me — that needs the
> ExecuTorch multimodal runner, not the single-`forward` path, so the decode wiring differs.

## QNN / NPU note

A QNN-delegated `.pte` also needs matching `libQnn*.so` native libs in
`app/src/main/jniLibs/arm64-v8a/`, and the QNN **runtime version in the app must match the QNN
SDK version used at export** (mismatch -> `Error 5000 / Qnn API version mismatched` at load).
Tell me the QNN SDK version used so I can align `shared/build.gradle.kts`.

(This `.md` file is ignored at runtime — only real model assets are loaded.)
