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
