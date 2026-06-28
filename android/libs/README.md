# ExecuTorch QNN AAR

`executorch-qnn.aar` is copied from Jainil's handoff (`udit-full-handoff`). It bundles:

- `org.pytorch.executorch.Module` (generic `.pte` forward — used by Places365 classifier)
- `org.pytorch.executorch.extension.llm.LlmModule` (SmolVLM 3-PTE multimodal path)
- `libexecutorch.so` + `libqnn_executorch_backend.so` (arm64)

Gradle prefers this AAR over Maven `executorch-android` when the file exists (see `:shared/build.gradle.kts`).

Refresh from handoff:

```bash
HANDOFF=~/Downloads/udit-full-handoff ./tools/stage_vlm_assets.sh
```
