package com.echowalk

import com.echowalk.teama.RadarState

/**
 * Accumulates YOLO detections over a sliding time window, extracts a stable object set, and
 * matches it against [SpatialMemory].
 *
 * Every [RECORD_INTERVAL_MS] it:
 *  1. Computes the "stable" object classes — those seen in ≥ [STABLE_FRACTION] of recent frames.
 *  2. Infers a room type via [RoomTypeClassifier] (e.g. "kitchen", "bathroom").
 *  3. Calls [SpatialMemory.record] with that set + bearings + inferred type.
 *  4. Fires [onRoomDetected] on every recognition (first-visit or return visit), rate-limited
 *     to [ANNOUNCE_COOLDOWN_MS]. The callback includes whether it's a return visit so the
 *     caller can say "You appear to be in a kitchen" vs "You're back in the kitchen".
 */
class RoomRecognizer(
    private val memory: SpatialMemory,
    /**
     * Called when a place is recognised.
     * @param roomType    e.g. "kitchen", "bathroom", "area"
     * @param isReturn    true if visited before (visitCount > 1 before this visit)
     * @param recalls     last known bearings for objects in this place
     */
    private val onRoomDetected: (roomType: String, isReturn: Boolean, recalls: Map<String, Float>) -> Unit,
) {
    private val window = ArrayDeque<Map<String, Float>>(WINDOW_FRAMES)

    private var lastRecordMs = 0L
    private var lastAnnounceMs = 0L
    private var lastAnnouncedRoomType = ""

    fun process(state: RadarState) {
        if (state.hazards.isEmpty()) return

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

    fun flush() {
        evaluate(System.currentTimeMillis())
        window.clear()
    }

    private fun evaluate(now: Long) {
        if (window.size < MIN_WINDOW_FRAMES) return

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

        val avgBearings = stableClasses.associateWith { cls ->
            bearingSum[cls]!! / classCounts[cls]!!
        }

        val roomType = RoomTypeClassifier.classify(stableClasses)
        val wasKnown = memory.matchPlace(stableClasses) != null
        val place = memory.record(stableClasses, avgBearings, roomType) ?: return

        // Announce when: enough cooldown elapsed AND room type changed (entered a different room).
        val cooldownOk = now - lastAnnounceMs > ANNOUNCE_COOLDOWN_MS
        val roomChanged = roomType != lastAnnouncedRoomType
        if (cooldownOk || roomChanged) {
            lastAnnounceMs = now
            lastAnnouncedRoomType = roomType
            onRoomDetected(roomType, wasKnown, place.objectBearings.toMap())
        }
    }

    companion object {
        private const val WINDOW_FRAMES = 30
        private const val MIN_WINDOW_FRAMES = 10
        private const val STABLE_FRACTION = 0.5f
        private const val RECORD_INTERVAL_MS = 3_000L
        private const val ANNOUNCE_COOLDOWN_MS = 90_000L
    }
}
