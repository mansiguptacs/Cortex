package com.echowalk

import com.echowalk.teama.RadarState

/**
 * Accumulates YOLO detections over a sliding time window, extracts a stable object set, and
 * matches it against [SpatialMemory].
 *
 * Every [RECORD_INTERVAL_MS] it:
 *  1. Computes the "stable" object classes — those seen in ≥ [STABLE_FRACTION] of recent frames.
 *  2. Calls [SpatialMemory.record] with that set + current bearings.
 *  3. If the result is a **known** place (visitCount > 1) and we haven't announced it recently,
 *     fires [onFamiliarPlace] with the stored object bearings for contextual recall hints.
 *
 * [onFamiliarPlace] is called at most once per [ANNOUNCE_COOLDOWN_MS] (90 s) to avoid spamming.
 */
class RoomRecognizer(
    private val memory: SpatialMemory,
    /** Called when a previously-visited place is recognised. Map = object → last known bearing. */
    private val onFamiliarPlace: (recalls: Map<String, Float>) -> Unit,
) {
    // Ring buffer: list of (objectClass → azimuthDeg) snapshots, one per processed frame.
    private val window = ArrayDeque<Map<String, Float>>(WINDOW_FRAMES)

    private var lastRecordMs = 0L
    private var lastAnnounceMs = 0L

    /**
     * Feed a new [RadarState] into the recognizer. Call this on every radar tick.
     */
    fun process(state: RadarState) {
        if (state.hazards.isEmpty()) return

        // Snapshot: best azimuth per class in this frame (highest depth = most confident).
        val snapshot = state.hazards
            .groupBy { it.cls }
            .mapValues { (_, vs) -> vs.maxByOrNull { it.distanceM }!!.azimuthDeg }
        if (window.size >= WINDOW_FRAMES) window.removeFirst()
        window.addLast(snapshot)

        val now = System.currentTimeMillis()
        if (now - lastRecordMs < RECORD_INTERVAL_MS) return
        lastRecordMs = now

        evaluate(now)
    }

    /** Force-flush the current window (e.g. when entering a new mode). */
    fun flush() {
        val now = System.currentTimeMillis()
        evaluate(now)
        window.clear()
    }

    private fun evaluate(now: Long) {
        if (window.size < MIN_WINDOW_FRAMES) return

        // Stable objects: seen in at least STABLE_FRACTION of window frames.
        val classCounts = mutableMapOf<String, Int>()
        val bearingSum  = mutableMapOf<String, Float>()
        for (snap in window) {
            snap.forEach { (cls, bearing) ->
                classCounts[cls] = (classCounts[cls] ?: 0) + 1
                bearingSum[cls]  = (bearingSum[cls]  ?: 0f) + bearing
            }
        }
        val minFrames = (window.size * STABLE_FRACTION).toInt().coerceAtLeast(1)
        val stableClasses = classCounts.filter { (_, count) -> count >= minFrames }.keys
        if (stableClasses.size < 2) return

        // Average bearing for each stable class.
        val avgBearings = stableClasses.associateWith { cls ->
            bearingSum[cls]!! / classCounts[cls]!!
        }

        val place = memory.record(stableClasses, avgBearings) ?: return

        // Announce "familiar place" only if: known (visited >1×), and cooldown elapsed.
        if (place.visitCount > 1 && now - lastAnnounceMs > ANNOUNCE_COOLDOWN_MS) {
            lastAnnounceMs = now
            onFamiliarPlace(place.objectBearings.toMap())
        }
    }

    companion object {
        /** Number of frames to hold in the sliding window (~3s at ~10 FPS). */
        private const val WINDOW_FRAMES = 30
        /** Need at least this many frames before evaluating. */
        private const val MIN_WINDOW_FRAMES = 10
        /** Fraction of window frames an object must appear in to be considered "stable". */
        private const val STABLE_FRACTION = 0.5f
        /** How often to evaluate + record (ms). */
        private const val RECORD_INTERVAL_MS = 3_000L
        /** Minimum gap between "familiar place" announcements (ms). */
        private const val ANNOUNCE_COOLDOWN_MS = 90_000L
    }
}
