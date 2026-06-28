# Bounding Box Models & QNN Libraries Added

**Branch:** `mohit-find-mode`  
**Date:** 2026-06-28  
**Purpose:** Enable real-time object detection (YOLO) and depth estimation for bounding box visualization

## Files Added (gitignored but present in APK)

### Detection & Depth Models
- `android/app/src/main/assets/yolov10_det.tflite` (8.9 MB)
  - YOLOv10 object detector for COCO classes
  - Runs on Hexagon NPU via QNN delegate
  - 640×640 input, ~30 FPS on S25 Ultra

- `android/app/src/main/assets/depth_anything_v2.tflite` (94 MB)
  - Depth-Anything-V2 monocular depth estimator
  - 518×518 input, cached every 2 frames to halve load
  - Provides proximity warnings

- `android/app/src/main/assets/coco.names` (621 B)
  - COCO label set (80 classes)
  - Used for object class names in boxes & spatial audio

### QNN Runtime Libraries
- `android/app/src/main/jniLibs/arm64-v8a/libQnnHtp.so` (3.3 MB) — QNN HTP accelerator
- `android/app/src/main/jniLibs/arm64-v8a/libQnnHtpV79Skel.so` (11 MB) — Hexagon DSP skeleton (v79)
- `android/app/src/main/jniLibs/arm64-v8a/libQnnHtpV79Stub.so` (719 KB) — FastRPC stub
- `android/app/src/main/jniLibs/arm64-v8a/libQnnSystem.so` (3.7 MB) — QNN core
- `android/app/src/main/jniLibs/arm64-v8a/libQnnTFLiteDelegate.so` (1.0 MB) — TFLite↔QNN bridge
- `android/app/src/main/jniLibs/arm64-v8a/libqnn_delegate_jni.so` (273 KB) — JNI wrapper

### Build Support
- `android/shared/libs/qnn-tflite-delegate.jar` (24 KB)
  - Gradle dependency for TFLite QNN delegate

## Architecture

### Detection Pipeline (SafetyRadarController)
1. Camera frame → CameraX provider (30 FPS)
2. CPU preprocess: resize to 640×640 (YOLO) + 518×518 (Depth)
3. NPU inference:
   - YOLO: runs every frame → bounding boxes + scores
   - Depth: runs every 2nd frame (reuse cached map)
4. Post-process: NMS, label lookup, spatial fusion
5. Emit `RadarState` with hazards list
6. UI renders boxes via `ObjectOverlayView.onDraw()`

### Bounding Box Rendering (ObjectOverlayView)
- **Target object** (Find mode): Bright green box + arrow label
- **Other objects**: Semi-transparent orange boxes + labels
- Confidence threshold: 35%
- Updates every frame (thread-safe)

## Integration Points

### In Code
- **MainActivity.kt** (lines 49, 93, 136–148): Overlay wired to radar state
- **SafetyRadarController.kt** (lines 37–212): YOLO/depth NPU runners
- **ObjectOverlayView.kt** (lines 1–93): Box renderer
- **activity_main.xml** (lines 19–27): Overlay UI layer

### Via Gradle
- `teama` module uses `qnn-tflite-delegate` to bridge TFLite ↔ QNN
- Dependency configured in `android/teama/build.gradle.kts`

## Testing

On device (S25 Ultra):
```bash
# Build with models included
gradle :app:assembleDebug

# Install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.echowalk/.MainActivity

# Tap "Describe" to see:
# ✅ Bounding boxes on camera feed
# ✅ Spatial audio cues ("Person 2 o'clock, 3 meters")
# ✅ Find mode highlights target object in green
```

## Known Issues

1. **JDK/Gradle build errors on macOS** — Workaround: use prebuilt APK or manually add assets
2. **Protocol mismatch for VLM** — `.pte` files expect different ExecuTorch version (separate issue)
3. **Skel version mismatch** — Both v79 skels present; ensure one matches host QAIRT 2.46.0

## Next Steps

- [ ] Verify boxes appear on S25 Ultra with new APK
- [ ] Profile latency per frame (target: <100ms total inference)
- [ ] Add accessibility labels for box coordinates
- [ ] Consider edge filtering (suppress boxes at image borders)
