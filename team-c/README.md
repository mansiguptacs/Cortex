# Team C — Familiar Places

Self-contained module for EchoWalk's "familiar places" mode (visual place recognition +
waypoint guidance). Kept in its own directory so it builds and tests independently of the
`android/` app and the `ml/` export scripts.

Plan: `../.cursor/plans/team_c_familiar_places.plan.md`

## Layout
- `core/` — pure Kotlin (JVM) logic with **zero Android/NPU dependencies** (Lane 1). Runs and
  tests on any JDK 21, no device required.
  - `Vectors` — cosine / L2 math for embeddings.
  - `CosineMatcher` — nearest-landmark search, max-over-embeddings, conservative stay-silent gate.
  - `PlaceStore` — storage abstraction (`InMemoryPlaceStore` for tests; `FilePlaceStore` persists
    enrolled places across restarts (proves M-C1); Room/SQLite later in the app).
  - `EmbeddingCodec` — `FloatArray`↔`ByteArray` packing (reused by the app's Room TypeConverter).
  - `RateGate` — fixed-interval throttle so localization runs at ~1-2 Hz and never starves the radar.
  - `Embedder` — swappable encoder seam (real QNN CLIP in the app; CPU fallback here).
  - `DownsampleEmbedder` — pure-CPU "tiny-image" fallback embedder needing **no `.pte`/NPU**, so
    the full enroll→localize→guide pipeline runs and is tested today (plan §2.4 fallback).
  - `Model` — `Place` / `Landmark` / `PlaceCue` domain + contract types.

## Running with NO model files (fallback)
The CPU `DownsampleEmbedder` lets the entire pipeline run before the CLIP `.pte` exists. See
`core/src/test/kotlin/com/echowalk/places/FallbackPipelineTest.kt` for milestones M-C0..M-C3
executed end-to-end on synthetic camera scenes. In the Android app, construct `ImageEncoder()`
with no arguments for the same fallback; pass an `EtModule` later to switch to the NPU encoder —
nothing downstream changes.

## Build & test
```bash
cd team-c
./gradlew :core:test
```
