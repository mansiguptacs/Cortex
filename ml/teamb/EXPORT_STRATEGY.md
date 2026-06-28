# Team B — SmolVLM export strategy (Jainil)

**Decision:** Path **B/C hybrid** — primary workflow is **receive or compile ExecuTorch+QNN artifacts**, then **package for Udit**. Full AOT compile requires a Linux/Android NDK + QNN SDK host (not this Mac-only venv).

**Target device:** Samsung Galaxy S25 Ultra — `SOC_MODEL=SM8750` (Snapdragon 8 Elite)

**Primary model:** [HuggingFaceTB/SmolVLM-500M-Instruct](https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct)

**Do not use for demo:** LLaVA-1.5 (CPU/XNNPACK — too slow), Moondream2 (no confirmed S25 QNN path).

---

## Why SmolVLM is three `.pte` files (not one)

ExecuTorch's Qualcomm VLM flow (Meta-validated on Galaxy S25) splits the graph:

| Artifact | Role | ~size (SM8550 ref) |
| --- | --- | --- |
| `*_encoder*.pte` | Vision encoder | ~102 MB |
| `*_text_embedding*.pte` | Text embedding | ~95 MB |
| `*_decoder*.pte` | Autoregressive decoder | ~370 MB |

Reference: `executorch/examples/qualcomm/oss_scripts/llama/llama.py` with `--decoder_model smolvlm_500m_instruct`.

Our handoff folder uses stable names:

```
exports/
├── vlm_encoder.pte
├── vlm_text_embedding.pte
├── vlm_decoder.pte
└── tokenizer/
```

Udit's `SmolVlmSceneDescriber` must load all three via `EtModule` (or we extend the shared helper later).

---

## Path A — Pre-built artifacts (preferred, low compute)

1. Check [Qualcomm AI Hub](https://aihub.qualcomm.com/) for SmolVLM / VLM on Snapdragon 8 Elite.
2. Ask on-site Qualcomm DevRel for pre-exported SM8750 artifacts from the hackathon toolchain.
3. Copy `.pte` files into `ml/teamb/exports/` and run:

```bash
python ml/teamb/export_smolvlm.py --prebuilt-dir ml/teamb/exports --package
```

---

## Path B/C — Compile on a QNN host (fallback)

Requires: ExecuTorch built from source, QNN SDK 2.37+, `ANDROID_NDK_ROOT`, adb device.

```bash
# On Linux QNN host — NOT verified on macOS pip install
python examples/qualcomm/oss_scripts/llama/llama.py \
  -b build-android \
  -s "${SERIAL_NUM}" \
  -m SM8750 \
  --decoder_model smolvlm_500m_instruct \
  --model_mode hybrid \
  --prefill_ar_len 16 \
  --max_seq_len 1024 \
  --prompt "Describe this indoor scene for a blind user." \
  --image_path /path/to/test.jpg
```

Copy the artifact directory into `ml/teamb/exports/`, then run `export_smolvlm.py --prebuilt-dir ...`.

Docs: [ExecuTorch Qualcomm backend](https://pytorch.org/executorch/main/backends-qualcomm), [llama/VLM README](https://github.com/pytorch/executorch/blob/main/examples/qualcomm/oss_scripts/llama/README.md).

---

## Path D — Llama fallback (deadline only)

If SmolVLM blocked: Team A supplies YOLO labels; Jainil exports `Llama-3.2-1B-Instruct` `.pte` (AI Hub / same llama.py flow). See plan Phase 7.

---

## Tokenizer

Always downloaded locally (small, no QNN needed):

```bash
python ml/teamb/export_smolvlm.py --download-tokenizer
```

Uses `HuggingFaceTB/SmolVLM-500M-Instruct` → `ml/teamb/models/tokenizer/`.

---

## Jainil → Udit handoff

```bash
python ml/teamb/export_smolvlm.py \
  --prebuilt-dir ml/teamb/exports \
  --tokenizer-dir ml/teamb/models/tokenizer \
  --out-dir ml/teamb/exports/handoff-run \
  --package
```

Produces `ml/teamb/handoff/vlm-handoff.zip` + updates `IO_SPEC.md` checklist. Share zip out of band (gitignored).
