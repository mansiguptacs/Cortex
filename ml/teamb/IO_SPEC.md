# SmolVLM I/O spec — Team B → Udit

**Model:** HuggingFaceTB/SmolVLM-500M-Instruct  
**Target SoC:** SM8750 (Galaxy S25 Ultra) via ExecuTorch QNN HTP  
**Handoff artifacts:** three `.pte` files + `tokenizer/`

> Fill in measured values after first on-device run. Jainil updates checksums; Udit confirms latency.

---

## Files (drop in `android/app/src/main/assets/`)

| File | Role |
| --- | --- |
| `vlm_encoder.pte` | Vision encoder (image → visual tokens) |
| `vlm_text_embedding.pte` | Text token embedding |
| `vlm_decoder.pte` | Autoregressive LLM decoder (KV hybrid mode) |
| `tokenizer/` | Hugging Face tokenizer JSON (full folder) |

Stable names produced by `python ml/teamb/export_smolvlm.py --prebuilt-dir ... --package`.

---

## Prompt (instruct)

Default scene-description prompt for EchoWalk:

```
Describe this indoor scene clearly for a blind person. Mention layout, major objects, and anything in the walking path. Keep it to 2-3 sentences.
```

SmolVLM-Instruct uses a chat template — apply via tokenizer before embedding:

```python
messages = [
    {
        "role": "user",
        "content": [
            {"type": "image"},
            {"type": "text", "text": "<prompt above>"},
        ],
    }
]
# tokenizer.apply_chat_template(messages, add_generation_prompt=True)
```

Udit: mirror ExecuTorch `llama.py` SmolVLM preprocessing or decode on CPU with the same template.

---

## Image input (vision encoder)

| Field | Value | Status |
| --- | --- | --- |
| Source | `Frame.rgb` Bitmap, upright RGB | From shared `FrameProvider` |
| Resize | **384 × 384** (verify on first run) | TODO: confirm from artifact metadata |
| Color order | RGB | |
| Normalization | ImageNet mean/std OR model-specific (see HF config) | TODO: confirm |
| Dtype | float32 NCHW tensor | |

Reference repo: `SmolVLMEncoder` in ExecuTorch qualcomm llama scripts.

---

## Inference flow (three modules)

1. **Preprocess** camera frame → vision tensor.  
2. **Encoder** `vlm_encoder.pte`: image → visual feature tokens.  
3. **Embed prompt** with `vlm_text_embedding.pte` + tokenizer token ids.  
4. **Decode** `vlm_decoder.pte`: autoregressive generation (`max_new_tokens` ≈ **128**, `max_seq_len` ≈ **1024** per llama.py defaults).  
5. **Decode tokens** → string; trim for TTS.

ExecuTorch reference flags: `--model_mode hybrid`, `--prefill_ar_len 16`.

---

## Output

| Field | Value |
| --- | --- |
| Type | UTF-8 text, 2–3 sentences |
| Post-process | Strip special tokens; capitalize first letter; truncate > ~500 chars for TTS |
| Latency target | Few seconds on S25 NPU (not CPU fallback) |

---

## QNN runtime

- Delegate: ExecuTorch Qualcomm QNN (Hexagon HTP), **not** NNAPI.  
- Bundled `.so` libs: match shared `EtModule` / app `jniLibs/arm64-v8a/` (from shared foundation).  
- **One heavy inference at a time** — `ModeManager` pauses radar before VLM.

---

## Validation checklist (Udit)

- [ ] All three `.pte` load without OOM  
- [ ] Test photo → plausible room description  
- [ ] Runs on HTP/NPU (not multi-second CPU stall)  
- [ ] `TeamBHarnessActivity` speaks result via TTS  
- [ ] Hotkey path works through `AudioOutputManager` (M6)

---

## Fallback (only if SmolVLM fails)

Team A YOLO labels → Jainil `Llama-3.2-1B` `.pte` → text synthesis. Separate spec if activated.

---

## Regenerating handoff

```bash
pip install -r ml/teamb/requirements-handoff.txt
python ml/teamb/export_smolvlm.py --download-tokenizer
# after .pte files land in ml/teamb/exports/:
python ml/teamb/export_smolvlm.py \
  --prebuilt-dir ml/teamb/exports \
  --out-dir ml/teamb/exports/handoff-run \
  --package
```

Share `ml/teamb/handoff/vlm-handoff-*.zip` out of band.
