# Team B — ML export (`ml/teamb/`)

Committed: export scripts (e.g. `export_smolvlm.py`).

**Not committed** — local folders below (see `.gitignore`). Use these for weights, builds, and handoff zips.

| Folder | Use |
| --- | --- |
| `models/` | Downloaded checkpoints, Hugging Face weights, tokenizer sources |
| `exports/` | Built or downloaded `vlm.pte`, tokenizer copies |
| `handoff/` | Zips and bundles you send to Udit (out of band from git) |
| `cache/` | HF / torch cache, temp files during export |

Create them once if missing:

```bash
mkdir -p ml/teamb/{models,exports,handoff,cache}
```

Personal step-by-step plan: `donotcheckin/IMPLEMENTATION_PLAN.md` (local only).

## Quick start (Jainil)

```bash
python3 -m venv .venv
.venv/bin/pip install -r ml/teamb/requirements-handoff.txt

# Tokenizer (no QNN needed)
.venv/bin/python ml/teamb/export_smolvlm.py --download-tokenizer

# After .pte files are in exports/ (from QNN host or AI Hub):
.venv/bin/python ml/teamb/export_smolvlm.py \
  --prebuilt-dir ml/teamb/exports \
  --out-dir ml/teamb/exports/handoff-run \
  --package
```

See also: `EXPORT_STRATEGY.md`, `IO_SPEC.md`.
