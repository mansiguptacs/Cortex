# Team B — Scene Description (on-demand)

You own the "describe the room" feature and implement `SceneDescriber`.

## Deliverables
- `ml/teamb/export_smolvlm.py` -> `.pte` validated on device (+ tags+Llama fallback path).
- `teamb/SmolVlmSceneDescriber` : `SceneDescriber` — one `Frame` -> spoken-ready text.
- `teamb/tts/SpeechOutput` — Android `TextToSpeech` wrapper (on-device).
- Hotkey handling (volume key / full-screen tap) wired through `ModeManager`.

## Milestone
- **M6 Hotkey VLM:** press the hotkey in a real room -> hear a plausible description within a few
  seconds, fully offline.

## Test in isolation
Run `TeamBHarnessActivity` (a "Describe" button captures one frame and speaks the result).
No dependency on A or C.

## Models
- Primary: `SmolVLM-500M` over QNN HTP (Meta validated the VLM flow on the Galaxy S25).
- Avoid LLaVA-1.5 for the live demo (XNNPACK/CPU-only in ExecuTorch -> seconds slow).
- Fallback: YOLO/MobileNet tags -> on-device `Llama-3.2-1B` to synthesize a description.

## Don't
- Don't keep the camera open during inference longer than needed.
- Don't speak while the radar is mid-alert — route all speech through `AudioOutputManager`.
