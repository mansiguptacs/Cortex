# Interface contracts (the integration seams)

These are the seams between the shared foundation and the three teams. Build the shared foundation
first, then **freeze these**. A team may change the internals behind its interface freely; changing
the interface signature requires a heads-up to the other teams.

## Shared types

```kotlin
// One camera frame. Team A subscribes continuously; Teams B & C grab single frames.
data class Frame(
    val rgb: android.graphics.Bitmap, // upright RGB (rotation already applied)
    val width: Int,
    val height: Int,
    val tsMs: Long,
)

// Source of frames. Only this touches CameraX.
interface FrameProvider {
    fun latest(): Frame?
    fun subscribe(listener: (Frame) -> Unit)
    fun unsubscribe(listener: (Frame) -> Unit)
}

// Thin wrapper over org.pytorch:executorch-android. One place to get the QNN libs right.
interface EtModule {
    // Single float input/output convenience; add typed variants as needed.
    fun forward(input: FloatArray, inputShape: IntArray): Array<FloatArray>
    fun close()
}
```

## Team A — Safety Radar

```kotlin
enum class HazardKind { WALL, OBSTACLE, DROPOFF }

data class Hazard(
    val cls: String,         // e.g. "chair", "person", "" for raw depth
    val distanceM: Float,    // relative/estimated meters
    val azimuthDeg: Float,   // - left, 0 center, + right
    val kind: HazardKind,
)

data class RadarState(
    val zoneNearestM: FloatArray, // [left, center, right]
    val hazards: List<Hazard>,
    val tsMs: Long,
)

interface SafetyRadar {
    fun start()
    fun stop()
    fun observe(listener: (RadarState) -> Unit)
}
```

## Team B — Scene Description

```kotlin
interface SceneDescriber {
    // Runs the VLM on one frame and returns a spoken-ready description.
    suspend fun describe(frame: Frame): String
}
```

## Team C — Familiar Places

```kotlin
enum class CueKind { LOCATED, APPROACHING_LANDMARK, TURN, ARRIVED }

data class PlaceCue(
    val kind: CueKind,
    val label: String,         // e.g. "your desk", "Aisle 7"
    val confidence: Float,
    val directionDeg: Float?,  // optional heading hint
    val distanceHint: String?, // optional, e.g. "a few steps"
)

interface PlaceNavigator {
    // Enrollment (learning walk)
    fun enrollStart(placeId: String)
    fun addLandmark(label: String)
    fun enrollStop()

    // Navigation
    fun listDestinations(): List<String>
    fun navigateTo(label: String)
    fun stopNavigation()

    // Low-rate localization + guidance cues
    fun observe(listener: (PlaceCue) -> Unit)
}
```

## Arbitration (owned by the shared `ModeManager` + `AudioOutputManager`)

- **Default:** `SafetyRadar` is always on (safety first). `PlaceNavigator` cues layer in at a low
  rate (~1-2 Hz) so it shares the NPU without starving the radar.
- **On hotkey:** pause heavy inference, run `SceneDescriber.describe()`, speak via TTS, then resume.
- **One NPU at a time for heavy work:** never run the VLM concurrently with the radar loop.
- **One audio bus:** `AudioOutputManager` orders radar tones vs. guidance vs. TTS so they don't
  overlap (TTS/guidance ducks the radar tones briefly).
