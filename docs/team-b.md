# Team B — Scene Description (on-demand)

**Owners:** Jainil (ML export) + Udit (Android integration)  
**Branch:** `team-b/scene-description`

You own the "describe the room" feature and implement `SceneDescriber`.

## Current status (Jun 27)

| Area | Status |
| --- | --- |
| SmolVLM 3× `.pte` + tokenizer + IO spec | ✅ Jainil (`udit-full-handoff`) |
| adb NPU validation (`qnn_multimodal_runner`) | ⏳ Blocked on QAIRT 2.46.0 skel |
| CPU hedge (Places365 `classifier.pte`) | ✅ ~68 ms on device |
| `TeamBHarnessActivity` (isolation test) | ✅ Full UX |
| Main app (`MainActivity` + `ModeManager`) | ✅ Team B integrated |
| In-app SmolVLM via `LlmModule` (3-PTE) | ✅ Scaffolded — pending skel + on-device test |
| M6 acceptance (SmolVLM on NPU, tap → speech) | ⏳ After skel |

## Deliverables
- `ml/teamb/export_smolvlm.py` + handoff zip → 3× `.pte` + tokenizer (Jainil).
- `teamb/vlm/LlmModuleSceneDescriber` — 3-PTE SmolVLM over QNN `LlmModule` (Udit).
- `teamb/vlm/ClassifierSceneDescriber` — Places365 CPU hedge (**done**).
- `SceneDescribers.create()` factory: VLM → classifier → Mock (**done**).
- Tap / double-tap describe + ambient auto-mode via `ModeManager` (**done**).
- All speech + earcons through `AudioOutputManager` (**done**).

## Milestone
- **M6 Hotkey VLM:** tap the preview (or Describe button) in a real room → hear a plausible
  2–3 sentence description within a few seconds on NPU, fully offline.
- **Ambient (auto):** brief change-only cues ("Kitchen.") via fast classifier — not full VLM.

## Test paths
1. **EchoWalk** (main app icon) — integrated demo with Places365 today; SmolVLM when wired.
2. **EchoWalk Team B** (harness launcher) — same engine, extra debug HUD + thumbnail.

## Models
- **Primary:** `SmolVLM-500M` — 3 PTE files + tokenizer over QNN HTP (NOT a single `vlm.pte`).
- **CPU hedge (live today):** ResNet18-Places365 scene classifier.
- **Fallback (stretch):** Team A YOLO tags → Llama-3.2-1B text synthesis.

## Blocker
See `ml/teamb/RUNTIME_NEEDED.md` — need `libQnnHtpV79Skel.so` **v2.46.0** from Jainil.

## Don't
- Don't run full VLM in ambient loop (too slow/heavy).
- Don't speak over radar alerts — route through `AudioOutputManager`.
- Don't hijack volume keys in the main app (tap = eyes-free trigger).
