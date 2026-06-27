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
  - `PlaceStore` — storage abstraction (`InMemoryPlaceStore` now; Room/SQLite later in the app).
  - `Model` — `Place` / `Landmark` / `PlaceCue` domain + contract types.

## Build & test
```bash
cd team-c
./gradlew :core:test
```
