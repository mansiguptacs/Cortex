package com.echowalk

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.HazardKind
import com.echowalk.teama.RadarState

/**
 * Translates [RadarState] events into spoken proximity warnings via [AudioOutputManager.speak].
 *
 * Runs alongside [com.echowalk.teama.audio.SpatialAudioEngine]:
 *  - SpatialAudioEngine is the *fast* channel (~140 ms cadence beeps + haptic).
 *  - VoiceWarningEngine is the *slow* semantic channel — fires only on state transitions,
 *    rate-limited so the user isn't flooded with speech.
 *
 * Triggers:
 *  1. A new YOLO hazard class appears in frame for the first time (or after a 5 s gap).
 *  2. A hazard's urgency band escalates (SOFT→MID or MID→URGENT).
 *  3. Flat-wall-close: all zones are finite + low (< FLAT_WALL_ZONE_MAX) with little spread
 *     (< FLAT_WALL_SPREAD_MIN) — this is the silent case where SpatialAudioEngine is quiet.
 */
class VoiceWarningEngine(private val audio: AudioOutputManager) {

    // Per-hazard-class: last time it was announced and last urgency band announced.
    private data class HazardMemory(var lastAnnouncedMs: Long = 0L, var lastBand: Int = -1)
    private val hazardMemory = HashMap<String, HazardMemory>()

    private var lastFlatWallMs = 0L
    private val startedAtMs = System.currentTimeMillis() // grace period starts at construction

    fun process(state: RadarState, safetyOnlyMode: Boolean = false) {
        if (audio.isSpeaking()) return
        // Don't fire any warnings during the startup grace period — phone is still being picked up.
        if (System.currentTimeMillis() - startedAtMs < STARTUP_GRACE_MS) return
        val now = System.currentTimeMillis()

        checkFlatWall(state, now)
        if (!safetyOnlyMode) checkHazards(state, now)
    }

    private fun checkFlatWall(state: RadarState, now: Long) {
        val zones = state.zoneNearestM.filter { it.isFinite() }
        if (zones.size < 2) return
        val zMax = zones.max()
        val zMin = zones.min()
        val spread = zMax - zMin
        // All zones close (normalised depth < threshold) with very little spread → uniform wall
        if (zMax < FLAT_WALL_ZONE_MAX && spread < FLAT_WALL_SPREAD_MIN) {
            if (now - lastFlatWallMs > FLAT_WALL_RATE_MS) {
                lastFlatWallMs = now
                audio.speak("Wall ahead, very close", flush = false)
            }
        }
    }

    private fun checkHazards(state: RadarState, now: Long) {
        for (h in state.hazards) {
            if (h.kind == HazardKind.WALL) continue // walls handled by flat-wall check + beeps
            val band = urgencyBand(h.distanceM)
            val mem = hazardMemory.getOrPut(h.cls) { HazardMemory() }
            val isNew = (now - mem.lastAnnouncedMs) > HAZARD_REPEAT_MS
            val escalated = band > mem.lastBand && (now - mem.lastAnnouncedMs) > ESCALATION_RATE_MS

            if (!isNew && !escalated) continue

            val phrase = when {
                h.kind == HazardKind.DROPOFF -> "Watch your step"
                escalated && band == 2 -> "Getting very close to ${friendlyName(h.cls)}"
                isNew -> {
                    val dir = when {
                        h.azimuthDeg < -13f -> "on your left"
                        h.azimuthDeg >  13f -> "on your right"
                        else -> "ahead"
                    }
                    "${friendlyName(h.cls).replaceFirstChar { it.uppercase() }} $dir"
                }
                else -> continue
            }

            mem.lastAnnouncedMs = now
            mem.lastBand = band
            audio.speak(phrase, flush = false)
            return // one warning per radar tick to avoid stacking phrases
        }
    }

    /** Flush memory when mode changes or new scene starts. */
    fun reset() {
        hazardMemory.clear()
        lastFlatWallMs = 0L
    }

    private fun urgencyBand(rel: Float): Int = when {
        rel >= 8.5f -> 2
        rel >= 6.5f -> 1
        rel >= 5.0f -> 0
        else -> -1
    }

    private fun friendlyName(cls: String): String = FRIENDLY.getOrDefault(cls, cls)

    companion object {
        // Flat-wall: all zones below this normalised depth with very small spread = uniform surface.
        private const val FLAT_WALL_ZONE_MAX = 5.0f
        private const val FLAT_WALL_SPREAD_MIN = 2.0f
        private const val FLAT_WALL_RATE_MS = 4_000L

        // Silence all warnings for this long after launch (phone is still being oriented).
        private const val STARTUP_GRACE_MS = 4_000L

        // Don't repeat the same hazard class faster than this.
        private const val HAZARD_REPEAT_MS = 5_000L
        // Allow escalation announcements with a shorter gate.
        private const val ESCALATION_RATE_MS = 2_500L

        private val FRIENDLY = mapOf(
            "person" to "someone",
            "chair" to "chair",
            "couch" to "couch",
            "dining table" to "table",
            "tv" to "screen",
            "laptop" to "laptop",
            "cup" to "cup",
            "bottle" to "bottle",
            "backpack" to "bag",
            "suitcase" to "suitcase",
            "door" to "doorway",
            "refrigerator" to "fridge",
            "drop" to "step",
        )
    }
}
