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

## Models
- Depth: `Depth-Anything-V2-Small`, QNN INT8/w8a16 (~25 ms on 8 Elite). First-party export script
  exists in `executorch/examples/qualcomm/oss_scripts`.
- Detection: YOLO nano (AI Hub).

## Don't
- Don't run heavy inference concurrently with the VLM (ModeManager handles pausing).
- Don't touch CameraX directly — consume `Frame` from the shared `FrameProvider`.
