# Team C — Familiar Places (learned spaces)

You help users navigate places they revisit (classroom, grocery store, office) and implement
`PlaceNavigator`. The personal "map" (embeddings + labels) stays on the device.

## Deliverables
- `ml/teamc/export_clip.py` -> CLIP/MobileCLIP image-encoder `.pte` validated on device.
- `teamc/embedding/ImageEncoder` — `Frame` -> embedding vector (runs on NPU, low rate ~1-2 Hz).
- `teamc/db/PlaceStore` — on-device storage of `{placeId, label, embedding, heading}`.
- `teamc/PlaceNavigatorImpl` : `PlaceNavigator` — enrollment, localization, waypoint guidance.

## Milestones (each must pass its test)
- **M-C0 Embedding proof:** same-spot photos high cosine similarity, different spots low.
- **M-C1 Enroll + persist:** enroll 3 landmarks; they survive an app restart.
- **M-C2 Localize:** revisiting an enrolled spot announces it; a random spot -> no false match.
- **M-C3 Guide:** pick a destination -> sequential cues to arrival.

## Test in isolation
Run `TeamCHarnessActivity` (enroll landmarks from camera or a saved video, then localize/guide).
No dependency on A or B.

## Model
- CLIP / MobileCLIP image encoder (AI Hub), QNN on NPU, via the shared `EtModule`.

## Reliability tips
- Use a conservative similarity threshold: stay silent when unsure (no false guidance).
- Enroll multiple frames per landmark; pre-enroll the demo space the night before.
- Fallback: landmark-announce-only (drop turn-by-turn) if relocalization is shaky.

## Stretch
- On-device OCR for aisle signs / room numbers.
- IMU-heading route record/replay for metric-ish turn-by-turn.

## Don't
- Don't run the encoder every frame — throttle to ~1-2 Hz to avoid starving the safety radar.
- Don't upload or sync the place DB anywhere. On-device only is the whole point.
