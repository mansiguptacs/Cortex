# EchoWalk — Android (multi-module)

Each team owns a Gradle module so they build and test in parallel without touching each other's
files.

| Module | Owner | Depends on | Contents |
| --- | --- | --- | --- |
| `:shared` | everyone (build first) | — | `Frame`, `FrameProvider`, `EtModule`, `ExecuTorchModule`, `CameraXFrameProvider`, `AudioOutputManager`, `AppMode` |
| `:teama` | Team A | `:shared` | `SafetyRadar` (iface) + controller + fusion + spatial audio + harness |
| `:teamb` | Team B | `:shared` | `SceneDescriber` (iface) + VLM impl + harness |
| `:teamc` | Team C | `:shared` | `PlaceNavigator` (iface) + impl + embedding + db + harness |
| `:app` | integration | all | `MainActivity`, `ModeManager`, layouts, app manifest |

Dependency rule: `:shared` has no team dependencies; no team module depends on another; only `:app`
wires all three. Don't break this — it's what keeps the builds independent.

## Setup

1. Open this `android/` folder in Android Studio. It syncs Gradle and creates the wrapper.
   (CLI: run `gradle wrapper` once if you have Gradle installed.)
2. Confirm the `org.pytorch:executorch-android` version in `shared/build.gradle.kts` against Maven
   Central, and align it with the QNN SDK version used to export `.pte` files.
3. QNN native libs -> `app/src/main/jniLibs/arm64-v8a/`. Exported `.pte` -> `app/src/main/assets/`.

## Build / run

- Whole app: `./gradlew :app:assembleDebug`
- A single team in isolation: `./gradlew :teama:assembleDebug` (or `:teamb`, `:teamc`)
- Launch a team harness on device:
  - `adb shell am start -n com.echowalk/com.echowalk.teama.TeamAHarnessActivity`
  - `adb shell am start -n com.echowalk/com.echowalk.teamb.TeamBHarnessActivity`
  - `adb shell am start -n com.echowalk/com.echowalk.teamc.TeamCHarnessActivity`

## Conventions

- Only `:shared` touches CameraX. Everyone else consumes `Frame`.
- All audio/speech goes through `AudioOutputManager`.
- Heavy inference is serialized by `ModeManager` (never run the VLM and the radar loop at once).
