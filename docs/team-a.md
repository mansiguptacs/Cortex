# Team A — Safety Radar (continuous)

You own the always-on obstacle-avoidance loop and implement `SafetyRadar`.

## Deliverables
- `ml/teama/export_depth.py`, `ml/teama/export_yolo.py` -> `.pte` files validated on device.
- `teama/SafetyRadarController` : `SafetyRadar` — frames -> depth + YOLO -> `RadarState`.
- `teama/fusion/DepthYoloFusion` — mask depth by each YOLO box -> per-object distance/azimuth;
  classify WALL vs OBSTACLE vs DROPOFF.
- `teama/audio/SpatialAudioEngine` — `RadarState` -> pitch/cadence (distance), L/R pan (azimuth),
  timbre (hazard vs guiding-hum vs drop-off) + haptics.

## Milestones (each must pass its test)
- **M1 Live depth in-app:** heatmap overlay updates live, on-screen FPS >= 10.
- **M2 MVP safety loop (depth-only):** walk toward a wall -> beeps speed up. FIRST DEMOABLE.
- **M3 Directional zones:** obstacle on the left -> left ear.
- **M4 Detection + fusion:** overlay shows "chair 1.4 m"; a wall hums instead of alarming.
- **M5 Semantic radar polish:** blindfolded teammate completes an obstacle course on audio alone.

## Test in isolation
Run `TeamAHarnessActivity` (camera or a saved image/video in, audio out). No dependency on B or C.

## Models (Path A: TFLite + QNN delegate, no ExecuTorch)
- Depth: `Depth-Anything-V2` (518×518 NHWC fp32), AI Hub TFLite export, runs on the QNN HTP
  delegate. Output is a *relative* depth map (`[1,518,518,1]`); **larger value = closer**.
- Detection: `YOLOv10-Det` (640×640 NHWC fp32), AI Hub TFLite export, QNN HTP delegate.
  Emits 8400 anchors with decoded boxes (pixel coords), per-anchor score, and uint8 class id.

Re-generate with `python ml/teama/export_{depth,yolo}.py`.

## Don't
- Don't run heavy inference concurrently with the VLM (ModeManager handles pausing).
- Don't touch CameraX directly — consume `Frame` from the shared `FrameProvider`.
