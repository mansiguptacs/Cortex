# Team C — Familiar Places (Visual Place Recognition + Guidance)

Companion to `on-device_blind_navigation_69ca1444.plan.md`. This is the single-owner build
guide for the "learned spaces" mode (milestones **M-C0 → M-C3**). It is the most deferrable
track, so it must stay **fully independent**: build and test against the camera (or saved
video) with stub versions of the shared pieces, and only swap to the real shell at integration.

---

## 1. Mission (what Team C delivers)

A mode that lets a blind/low-vision user **enroll a space once** (a "learning walk" with
voice-labeled landmarks) and, on return, **announces where they are** and **guides them to a
saved destination** — all on-device, all offline. The personal map is just embeddings + labels
in a local DB; raw images never leave the phone.

Output contract Team C must implement (already frozen in the master plan):

```kotlin
data class PlaceCue(val kind: CueKind, val label: String, val confidence: Float,
                    val directionDeg: Float?, val distanceHint: String?)
enum class CueKind { LOCATED, APPROACHING_LANDMARK, TURN, ARRIVED }
interface PlaceNavigator {
  fun enrollStart(placeId: String); fun addLandmark(label: String); fun enrollStop()
  fun listDestinations(): List<String>; fun navigateTo(label: String); fun stopNavigation()
  fun observe(cb: (PlaceCue) -> Unit)
}
```

You own: a CLIP/MobileCLIP image-encoder `.pte`, an on-device place DB, cosine-similarity
localization, and sequential waypoint guidance. You consume frames via `FrameProvider` and
speak via the shared audio bus — but during solo dev you mock both.

---

## 2. Prerequisites (have these GREEN before writing Team C code)

### 2.1 Hard blockers (shared, from M0 — cannot start the NPU model without these)
- **Working ExecuTorch + Qualcomm QNN export host** (Linux + Android NDK + QAIRT/QNN SDK).
  This is the M0 toolchain. If M0 isn't green, you can still start (see 2.4 fallback).
- **A Galaxy S25 Ultra reachable over `adb`**, with the `executorch-android` AAR + QNN/QAIRT
  `.so` libs that the shared `EtModule` helper bundles.
- **The frozen interface contracts** (`Frame`, `FrameProvider`, `EtModule`, `PlaceNavigator`)
  — do not redefine these; import them so integration is mechanical.

### 2.2 Things you should grab early (save hours)
- The **pre-exported OpenAI-CLIP image-encoder** from Qualcomm AI Hub / HF (`qualcomm/OpenAI-Clip`)
  in QNN form (see §3 — this is the locked model). Prefer the pre-exported artifact over exporting
  yourself. Image encoder only — you do **not** need the text tower for this feature.
- The ExecuTorch `examples/qualcomm/oss_scripts` export flow as the reference for exporting
  your own encoder if no pre-built artifact exists.
- `SimpleADB` (from the qualcomm examples) to validate any `.pte` on-device before app work.

### 2.3 App-side prerequisites
- The **app skeleton** (S1) must build and run the camera so you can pull real frames. Until
  it does, develop your logic in a **standalone harness Activity** with its own CameraX or a
  saved video/image set.
- Decide the **embedding store**: Room (SQLite) is the recommended choice — schema below.

### 2.4 If M0 / NPU isn't ready yet (unblock yourself)
- Prototype the **entire localization + guidance pipeline on CPU** using an XNNPACK-delegate
  CLIP encoder (or even a desktop CLIP) so cosine matching, the DB, thresholds, and cue logic
  are all proven. Swapping in the QNN `.pte` later is a one-line model change.
- This keeps Team C productive regardless of toolchain risk and is the explicit risk mitigation.

---

## 3. Tech decisions (pick these unless a mentor says otherwise)

- **Encoder (LOCKED): OpenAI-CLIP `ViT-B/16` image tower, from Qualcomm AI Hub.** This is the
  "allowed model" choice for Team C — it has a *confirmed QNN/AI-Hub path on the Galaxy S25
  Ultra / Snapdragon 8 Elite NPU* (AI Hub lists S25 Ultra explicitly; ~11–15 ms on NPU,
  224×224 input, ~512-d embedding). Same selection principle as the rest of the master plan
  (Depth-Anything-V2-S, YOLO nano, SmolVLM-500M) — pick what is *known to run on the S25 NPU*.
  - **Use the image encoder ONLY** — no text tower needed for place recognition.
  - **Export quantized (INT8 / w8a16) via AI Hub.** Float is 150M params / ~571 MB; quantize to
    shrink APK + memory and keep NPU-friendly. Grab the pre-exported AI Hub artifact if available.
  - Export command (reference): `python -m qai_hub_models.models.openai_clip.export`
    (`pip install qai-hub-models git+https://github.com/openai/CLIP.git`).
- **Stretch swap: MobileCLIP-S0/S1** (Apple `ml-mobileclip`) — smaller/faster FastViT encoder,
  but the ready-made QNN/S25 export path is less certain. Only swap if a Qualcomm mentor confirms
  a working export on the floor; the architecture is model-agnostic so swapping is a one-liner.
- **Inference rate:** ~1–2 Hz, NOT every frame. The radar (Team A) owns the NPU at high rate;
  you take occasional slices so you don't starve it. Throttle in your controller.
- **Matching:** brute-force cosine similarity over the stored vectors (a few hundred max) on
  CPU. No ANN index needed at this scale.
- **DB:** Room/SQLite. One `Place` has many `Landmark`s.
- **Multi-frame enrollment:** store **3–5 embeddings per landmark** (averaged or kept as a set)
  for robustness — single-shot embeddings are brittle to viewpoint.
- **Conservative threshold:** when max cosine < threshold, **stay silent**. False "you're at X"
  is worse than silence for this user.

Suggested Room schema:

```kotlin
@Entity data class Place(@PrimaryKey val id: String, val name: String)
@Entity data class Landmark(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val placeId: String, val label: String,
  val embedding: ByteArray,   // float[] packed; or FloatArray via TypeConverter
  val headingDeg: Float?,      // from IMU/rotation vector at capture time
  val orderIndex: Int          // capture order, used as the default route sequence
)
```

---

## 3b. Independent work streams (what Team C can do WITHOUT waiting on anyone)

The point of this section: **Team C never has to sit idle.** Split the work into three lanes by
dependency. Lanes 1 and 2 require *nothing* from M0, the app skeleton, or Teams A/B — start them
the moment the team forks. Lane 3 is the only part that touches the shared NPU plumbing, and even
that is unblockable (see fallback).

### Lane 1 — Pure-logic core (ZERO dependencies, start immediately)
Build and unit-test these against fake/desktop embeddings (random vectors or a desktop CLIP run);
no phone, no NPU, no `.pte` required.
- **Place DB**: Room entities (`Place`, `Landmark`), DAO, `FloatArray↔ByteArray` TypeConverter.
- **Matcher**: `cosine(query, stored)` + nearest-landmark search + conservative-threshold gate +
  debounce/hysteresis. Unit-tested with synthetic vectors (same-cluster → match, far → silent).
- **Route engine**: from enrolled `orderIndex` + headings, produce the ordered waypoint list and
  the `PlaceCue` sequence (`APPROACHING_LANDMARK` → `TURN` → `ARRIVED`).
- **`PlaceNavigator` implementation** wiring the above behind the frozen interface, emitting cues
  via `observe`. (Feed it embeddings from a stub source.)
- **Threshold/eval harness**: a tiny script that takes labeled image sets → embeddings → prints a
  similarity matrix + suggests a threshold. Reusable once real embeddings exist.

### Lane 2 — Standalone test app shell (ZERO dependency on the shared skeleton)
- A self-contained harness Activity with its **own** CameraX (or a saved-video reader) and a
  **stub `FrameProvider`/audio bus** (just log + Android TTS the cue text). This is your entire
  test environment for M-C1..M-C3 and is what makes the track independent.
- Build buttons: Start Enroll / Add Landmark (label field) / Stop Enroll / List / Navigate To,
  plus a live log of cosine scores and emitted cues.

### Lane 3 — The encoder on NPU (the ONLY shared-plumbing dependency)
- Get the OpenAI-CLIP image-encoder `.pte` (locked in §3), validate on device with `SimpleADB`,
  wrap behind shared `EtModule` → `Embedder.embed(frame): FloatArray` (L2-normalized).
- **Unblock if M0/QNN isn't ready:** run the encoder via **XNNPACK CPU delegate** or a desktop
  CLIP and feed embeddings into Lanes 1–2. The model is swappable, so the QNN `.pte` drops in
  later with a one-line change and **nothing downstream has to change**.

> Ordering: start Lane 1 + Lane 2 in parallel on hour one (they need nothing). Pull in Lane 3 as
> soon as either the AI Hub artifact or a CPU encoder is available — your pipeline is already
> proven, so it becomes a model swap, not a rebuild.

## 4. Step-by-step build (mapped to milestones)

### M-C0 — Embedding proof (Sat afternoon, H3–5)
**Goal:** encoder `.pte` runs on NPU and produces discriminative vectors.
1. Acquire/export the OpenAI-CLIP (ViT-B/16, quantized) image-encoder `.pte`; validate on device
   with `SimpleADB`.
2. Wrap it behind the shared `EtModule` (or a thin `Embedder` that calls `EtModule.forward`).
3. Preprocess: frame → resize to encoder input (e.g. 224×224) → normalize → tensor (reuse
   shared preprocessing helpers; keep off the UI thread).
4. L2-normalize the output vector.
5. Harness: capture two photos of the same spot and two of a different spot; print cosine sims.
- **DONE WHEN:** same-spot pairs score high cosine similarity, different-spot pairs score low.
  (Pick the threshold empirically from this data — write the number down; you'll reuse it.)

### M-C1 — Enroll + persist (Sat evening, H5–7)
**Goal:** a learning walk that saves voice-labeled landmarks locally.
1. Build the Room DB (`Place`, `Landmark`) + DAO + a `FloatArray`/`ByteArray` TypeConverter.
2. Implement `enrollStart(placeId)` / `addLandmark(label)` / `enrollStop()`:
   - On `addLandmark`, capture N frames, compute embeddings, store with label + current heading
     (rotation-vector sensor) + `orderIndex`.
   - Voice label input: simplest is a text field in the harness now; wire on-device speech (or a
     spoken-confirm + manual list) later. Voice is a polish, not a blocker.
3. Implement `listDestinations()` from the DB.
- **DONE WHEN:** enroll 3 landmarks, kill and relaunch the app, the 3 landmarks are still there.

### M-C2 — Localize (Sun morning, 8:30–10:30)
**Goal:** live frame → correct "near X"; unknown spot stays silent.
1. Localization loop at ~1–2 Hz: embed latest frame → cosine vs all stored landmarks.
2. If `max ≥ threshold` → emit `PlaceCue(LOCATED or APPROACHING_LANDMARK, label, confidence)`.
   Else emit nothing.
3. Add **hysteresis / debounce** so it doesn't flip-flop between adjacent landmarks (e.g.
   require K consecutive agreeing matches before announcing a change).
- **DONE WHEN:** revisiting an enrolled spot announces the right landmark; a random/unseen spot
  produces no false match.

### M-C3 — Guide (Sun morning, 10:30–12:30)
**Goal:** pick a destination → sequential cues to arrival.
1. `navigateTo(label)`: find the destination landmark; build the route as the ordered list of
   landmarks from the user's current best-match position up to the destination (use `orderIndex`).
2. As the user moves, track which landmark they're nearest to; emit:
   - `APPROACHING_LANDMARK` when close to the next waypoint,
   - `TURN` using stored `headingDeg` deltas vs current heading (direction = `directionDeg`),
   - `ARRIVED` when the destination landmark matches above threshold.
3. `stopNavigation()` clears state. All cues go out via `observe` → shared audio bus.
- **DONE WHEN:** from the entrance you're guided to "desk" via announced waypoints + arrival.

---

## 5. Independent test path (need nothing from Team A or B)
Standalone harness Activity:
- Buttons: **Start Enroll / Add Landmark (label field) / Stop Enroll / list / Navigate To**.
- A text log showing live cosine scores + emitted `PlaceCue`s.
- Drive from live camera OR a recorded walkthrough video for repeatable tests.
Acceptance = the four M-C tests above. Stub `FrameProvider` (feed it your frames) and stub the
audio bus (just log/TTS the cue text).

---

## 6. Integration handoff (INT)
- Replace your stub `FrameProvider` with the shared one; subscribe at low rate only.
- Route `PlaceCue`s through `AudioOutputManager` so guidance is ordered against radar tones/TTS.
- Confirm with `ModeManager`: radar stays always-on; your cues layer in at ~1–2 Hz so you don't
  starve the NPU; hotkey VLM still cleanly pauses heavy inference.
- Pre-enroll the actual demo space the night before M7.

---

## 7. Risks & fallbacks (own these)
- **NPU/QNN not ready:** develop on CPU/XNNPACK encoder (see 2.4); swap model in later.
- **Flaky matches / false positives:** raise threshold (stay silent when unsure), enroll more
  frames per landmark, add debounce/hysteresis, pre-enroll the demo space.
- **Relocalization shaky:** fall back to **landmark-announce-only** (drop turn-by-turn) — still
  a compelling "you're near your desk" demo.
- **NPU contention with radar:** hard-cap your inference to 1–2 Hz; never run every frame.
- **Heading unreliable indoors:** keep turn cues coarse (left/right/ahead) from rotation-vector
  deltas; don't promise degrees-accurate metric guidance.

---

## 8. One-line summary of deliverables
`OpenAI-CLIP ViT-B/16 image encoder .pte (NPU, AI Hub) → per-frame embedding → Room place DB
(enroll w/ voice labels + heading) → cosine localization with conservative threshold → sequential
waypoint guidance`, implementing `PlaceNavigator`, tested standalone, integrated last.
