# EchoWalk

On-device indoor navigation assistant for blind / low-vision users, running entirely on a
Samsung Galaxy S25 Ultra (Snapdragon 8 Elite NPU) via **PyTorch ExecuTorch + Qualcomm QNN**.

No cloud. No latency. The camera feed and your personal maps never leave the device.

## What it does

| Mode | Team | Trigger | Output |
| --- | --- | --- | --- |
| **Safety Radar** (continuous) | A | always-on | spatial audio + haptics for nearby obstacles, distinguishing a wall you can trail from a trip hazard |
| **Scene Description** (on-demand) | B | volume key / tap | a spoken description of the room from a VLM |
| **Familiar Places** (learned) | C | when in an enrolled space | localizes you and guides you to saved destinations |

See the full plan in [`.cursor/plans/on-device_blind_navigation_69ca1444.plan.md`](.cursor/plans/on-device_blind_navigation_69ca1444.plan.md).

## Repository layout

```
hackathon/
├── android/            # Multi-module Gradle project. Each team owns a module.
│   ├── shared/         # :shared  — foundation: camera, ExecuTorch helper, mode + audio (no team deps)
│   ├── teama/          # :teama   — Team A: Safety Radar (depends on :shared only)
│   ├── teamb/          # :teamb   — Team B: Scene Description (depends on :shared only)
│   ├── teamc/          # :teamc   — Team C: Familiar Places (depends on :shared only)
│   └── app/            # :app     — integration: MainActivity + ModeManager (depends on all)
├── ml/                 # Model export to .pte (Python / ExecuTorch + Qualcomm AI Hub)
│   ├── shared/         # common export helpers
│   ├── teama/  teamb/  teamc/
└── docs/               # interface contracts + per-team guides
```

### Why separate modules
Each team builds and tests its own module in isolation (`./gradlew :teama:assembleDebug`), so two
teams never edit the same files and compile in parallel. The dependency rule: `:shared` depends on
nothing team-specific, no team module depends on another, and only `:app` knows all three.

## How the three teams work in parallel

1. **Everyone first** builds the shared foundation (`android/.../shared/`) and **freezes the
   interfaces** in [`docs/interfaces.md`](docs/interfaces.md). After that, the seams don't change.
2. Each team implements exactly one interface and develops against the shared stubs, so no team
   blocks another:
   - Team A implements `SafetyRadar`
   - Team B implements `SceneDescriber`
   - Team C implements `PlaceNavigator`
3. Each team has a **standalone harness activity** to test its module in isolation (camera or a
   saved image/video in, audio out) without the other teams.
4. **Integration** is mechanical: swap the stub implementations for the real ones in
   `ModeManager`; verify NPU/audio arbitration.

The golden rule: **don't change a frozen interface without telling the other teams.**

## Getting started

- Android: open the `android/` folder in Android Studio (it will sync Gradle and create the
  wrapper). See [`android/README.md`](android/README.md).
- ML / model export: see [`ml/README.md`](ml/README.md).
- Contracts every team must honor: [`docs/interfaces.md`](docs/interfaces.md).

## Target device

Samsung Galaxy S25 Ultra (Snapdragon 8 Elite). Models run on the Hexagon NPU via the ExecuTorch
QNN delegate (NOT NNAPI).
