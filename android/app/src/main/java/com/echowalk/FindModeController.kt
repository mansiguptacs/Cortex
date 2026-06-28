package com.echowalk

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.RadarState

/**
 * Guides the user toward a named YOLO target class.
 *
 * Three phases:
 *  1. **SCAN** — target not yet seen. Announces every visible object to orient the user.
 *     Switches to NAVIGATE when target first appears.
 *  2. **NAVIGATE** — target visible. Azimuth-based directions + distance trend. Switches to
 *     REACH when target is close enough.
 *  3. **REACH** — target is nearby. Says "almost there" and fine-guides until REACH_DEPTH
 *     is hit, then declares arrived and calls [onFound].
 *
 * [ObjectMemory] stores the last known azimuth for each target so a follow-up "find the bottle"
 * immediately starts in NAVIGATE with a directional hint instead of a blank SCAN.
 */
class FindModeController(
    private val targetClass: String,
    private val audio: AudioOutputManager,
    private val onFound: () -> Unit,
    private val onLost: () -> Unit,
) {
    private enum class Phase { SCAN, NAVIGATE, REACH }

    private var phase = Phase.SCAN
    private var lastGuidanceMs = 0L
    private var lastSeenMs = 0L
    private var lastPhrase = ""
    private var lastElevationDeg = 0f  // last known vertical position of target
    private val announcedClasses = mutableSetOf<String>()
    private val distanceWindow = ArrayDeque<Float>(TREND_WINDOW)
    private var lastObstacleMs = 0L        // rate-limit for path obstacle warnings
    private val warnedObstacles = mutableSetOf<String>() // obstacles already warned this session

    fun process(state: RadarState) {
        val now = System.currentTimeMillis()

        val target = state.hazards
            .filter { it.cls == targetClass }
            .maxByOrNull { it.distanceM }

        if (target != null) ObjectMemory.update(targetClass, target.azimuthDeg)

        // CRITICAL: check SCAN→NAVIGATE transition BEFORE the isSpeaking() gate.
        // When the target appears, interrupt any ongoing scan announcement and switch immediately —
        // otherwise the phase can never advance while TTS is mid-sentence.
        if (phase == Phase.SCAN && target != null) {
            phase = Phase.NAVIGATE
            lastSeenMs = now
            lastElevationDeg = target.elevationDeg
            // flush=true interrupts the current scan announcement
            audio.speak(
                "Found it — ${friendlyName(targetClass)} ${directionPhrase(target.azimuthDeg)}. I'll guide you.",
                flush = true,
            )
            lastGuidanceMs = now
            return
        }

        // Gate all other voice output on isSpeaking() to avoid overlapping speech.
        if (audio.isSpeaking()) return

        return when (phase) {
            Phase.SCAN     -> processScan(state, target, now)
            Phase.NAVIGATE -> {
                checkPathObstacles(state, target, now)
                processNavigate(target, now)
            }
            Phase.REACH    -> processReach(target, now)
        }
    }

    /**
     * Warn about non-target objects that are in the forward path toward the target.
     * "In path" = azimuth within PATH_CONE_DEG of the target's direction (or center if no target),
     * and close enough to require a detour (distanceM ≥ OBSTACLE_WARN_DEPTH).
     */
    private fun checkPathObstacles(state: RadarState, target: com.echowalk.teama.Hazard?, now: Long) {
        if (now - lastObstacleMs < OBSTACLE_RATE_MS) return

        // Direction we're heading toward (target azimuth, or 0° if not yet visible)
        val headingDeg = target?.azimuthDeg ?: 0f

        val obstacle = state.hazards
            .filter { h ->
                h.cls != targetClass &&
                h.kind != com.echowalk.teama.HazardKind.WALL &&
                h.distanceM >= OBSTACLE_WARN_DEPTH &&
                kotlin.math.abs(h.azimuthDeg - headingDeg) <= PATH_CONE_DEG &&
                h.cls !in warnedObstacles
            }
            .maxByOrNull { it.distanceM } ?: return

        // Also clear warned objects that are no longer in the path (moved or passed)
        val currentPathClasses = state.hazards
            .filter { h -> h.cls != targetClass && kotlin.math.abs(h.azimuthDeg - headingDeg) <= PATH_CONE_DEG }
            .map { it.cls }.toSet()
        warnedObstacles.retainAll(currentPathClasses)

        val dir = when {
            obstacle.azimuthDeg < -13f -> "on your left"
            obstacle.azimuthDeg >  13f -> "on your right"
            else -> "in your path"
        }
        warnedObstacles.add(obstacle.cls)
        lastObstacleMs = now
        audio.speak("Watch out — ${friendlyName(obstacle.cls)} $dir", flush = false)
    }

    // During SCAN: announce a few visible objects to orient the user; switch to NAVIGATE when target appears.
    // NOTE: the SCAN→NAVIGATE transition itself is handled in process() before the isSpeaking() gate.
    private fun processScan(state: RadarState, target: com.echowalk.teama.Hazard?, now: Long) {
        if (now - lastGuidanceMs < SCAN_ANNOUNCE_RATE_MS) return

        // Cap at MAX_SCAN_OBJECTS so we don't build a 15+ second TTS chain that blocks detection.
        if (announcedClasses.size < MAX_SCAN_OBJECTS) {
            val newClass = state.hazards
                .filterNot { it.cls == targetClass || it.cls in announcedClasses }
                .maxByOrNull { it.distanceM }
            if (newClass != null) {
                announcedClasses.add(newClass.cls)
                audio.speak("I see a ${friendlyName(newClass.cls)} ${directionPhrase(newClass.azimuthDeg)}", flush = false)
                lastGuidanceMs = now
                return
            }
        }
        // Cap reached or no new objects — nudge user to keep rotating.
        if (now - lastGuidanceMs > SCAN_NUDGE_MS) {
            audio.speak("Keep turning slowly", flush = false)
            lastGuidanceMs = now
        }
    }

    // During NAVIGATE: directional guidance; switch to REACH when close enough.
    private fun processNavigate(target: com.echowalk.teama.Hazard?, now: Long) {
        if (target == null) {
            handleLost(now)
            return
        }
        lastSeenMs = now
        lastElevationDeg = target.elevationDeg
        if (distanceWindow.size >= TREND_WINDOW) distanceWindow.removeFirst()
        distanceWindow.addLast(target.distanceM)

        val centered = kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG

        // Transition to REACH: depth threshold OR box area large enough (more reliable up close).
        if (centered && (target.distanceM >= NEAR_DEPTH || target.boxArea >= BOX_AREA_REACH_ENTER)) {
            phase = Phase.REACH
            val msg = when {
                target.boxArea >= BOX_AREA_NEAR -> "Very close — slow down and reach out."
                else -> "Almost there — walk forward slowly."
            }
            audio.speak(msg, flush = false)
            lastGuidanceMs = now
            return
        }

        if (now - lastGuidanceMs < GUIDANCE_RATE_MS) return

        // Proximity hint within NAVIGATE so user isn't left in the dark between directions.
        val proximityHint = when {
            target.boxArea >= BOX_AREA_APPROACHING -> " — getting close"
            else -> ""
        }
        val phrase = buildCenteringPhrase(target.azimuthDeg, target.elevationDeg, target.distanceM) + proximityHint
        if (phrase == lastPhrase && now - lastGuidanceMs < REPEAT_SAME_RATE_MS) return
        lastGuidanceMs = now
        lastPhrase = phrase
        audio.speak(phrase, flush = false)
    }

    // During REACH: fine guidance with tilt hints; declare arrived when very close.
    private fun processReach(target: com.echowalk.teama.Hazard?, now: Long) {
        if (target == null) {
            // Object left the frame — infer direction from last known elevation.
            if (lastSeenMs > 0 && now - lastSeenMs < REACH_LOST_GRACE_MS) {
                val tiltHint = when {
                    lastElevationDeg < -ELEV_TILT_THRESHOLD ->
                        "Tilt the phone down — the ${friendlyName(targetClass)} is below you."
                    lastElevationDeg > ELEV_TILT_THRESHOLD ->
                        "Tilt the phone up — the ${friendlyName(targetClass)} is above you."
                    else ->
                        "The ${friendlyName(targetClass)} is right here — reach out and grab it!"
                }
                audio.speak(tiltHint, flush = false)
                onFound()
            } else {
                handleLost(now)
            }
            return
        }

        lastSeenMs = now
        lastElevationDeg = target.elevationDeg

        // Three-tier grab detection by box area:
        //  GRAB  (≥0.16): object fills a large portion of frame → arm reach distance
        //  NEAR  (≥0.09): object sizeable in frame → slow down, about to reach
        //  CLOSE (≥0.05): entered REACH but still some distance → careful guidance
        if (target.boxArea >= BOX_AREA_GRAB && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG) {
            audio.speak("Right here — reach out and grab the ${friendlyName(targetClass)}!", flush = false)
            onFound()
            return
        }
        if (target.boxArea >= BOX_AREA_NEAR && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG
            && now - lastGuidanceMs > GUIDANCE_RATE_MS) {
            audio.speak("Almost touching — slow down, the ${friendlyName(targetClass)} is right in front of you.", flush = false)
            lastGuidanceMs = now
            lastPhrase = "near"
            return
        }
        if (target.boxArea >= BOX_AREA_APPROACHING && now - lastGuidanceMs > GUIDANCE_RATE_MS) {
            val phrase = buildReachPhrase(target.azimuthDeg, target.elevationDeg)
            if (phrase != lastPhrase) {
                audio.speak("Getting very close — $phrase", flush = false)
                lastGuidanceMs = now
                lastPhrase = phrase
            }
            return
        }

        if (now - lastGuidanceMs < GUIDANCE_RATE_MS) return
        val phrase = buildReachPhrase(target.azimuthDeg, target.elevationDeg)
        if (phrase != lastPhrase) {
            lastGuidanceMs = now
            lastPhrase = phrase
            audio.speak(phrase, flush = false)
        }
    }

    private fun buildReachPhrase(azDeg: Float, elDeg: Float): String {
        val h = when {
            azDeg < -AZ_THRESHOLD -> "slightly left"
            azDeg >  AZ_THRESHOLD -> "slightly right"
            else -> null
        }
        val v = when {
            elDeg < -ELEV_TILT_THRESHOLD -> "tilt down"
            elDeg >  ELEV_TILT_THRESHOLD -> "tilt up"
            else -> null
        }
        return when {
            h != null && v != null -> "Go $h and $v"
            h != null              -> "Go $h"
            v != null              -> v.replaceFirstChar { it.uppercase() }
            else                   -> "Keep going — almost there"
        }
    }

    private fun handleLost(now: Long) {
        if (lastSeenMs > 0 && now - lastSeenMs > LOST_TIMEOUT_MS) {
            if (now - lastGuidanceMs > GUIDANCE_RATE_MS) {
                lastGuidanceMs = now
                audio.speak("Lost it — turn slowly", flush = false)
            }
            if (now - lastSeenMs > AUTO_EXIT_MS) {
                audio.speak("${friendlyName(targetClass)} not found. Exiting.", flush = false)
                onLost()
            }
        }
    }

    /** Combined left/right + tilt guidance to keep target centered in frame. */
    private fun buildCenteringPhrase(azDeg: Float, elDeg: Float, distanceM: Float): String {
        val h = when {
            azDeg < -40f -> "Turn sharply left"
            azDeg < -AZ_THRESHOLD -> "Turn left"
            azDeg < -8f  -> "Slightly left"
            azDeg >  40f -> "Turn sharply right"
            azDeg >  AZ_THRESHOLD -> "Turn right"
            azDeg >  8f  -> "Slightly right"
            else -> null
        }
        val v = when {
            elDeg < -ELEV_TILT_THRESHOLD -> "tilt down"
            elDeg >  ELEV_TILT_THRESHOLD -> "tilt up"
            else -> null
        }
        // If centered horizontally and vertically, give forward/distance guidance
        if (h == null && v == null) {
            return when (distanceTrend()) {
                Trend.APPROACHING -> "Keep going"
                Trend.RECEDING    -> "You're moving away — stop and turn"
                Trend.STABLE      -> if (distanceM >= 6f) "Walk forward" else "Walk forward slowly"
            }
        }
        return when {
            h != null && v != null -> "$h and $v"
            h != null -> h
            else -> v!!.replaceFirstChar { it.uppercase() }
        }
    }

    private fun directionPhrase(az: Float) = when {
        az < -13f -> "on your left"
        az >  13f -> "on your right"
        else      -> "ahead"
    }

    private enum class Trend { APPROACHING, RECEDING, STABLE }

    private fun distanceTrend(): Trend {
        if (distanceWindow.size < 2) return Trend.STABLE
        val delta = distanceWindow.last() - distanceWindow.first()
        return when {
            delta >  TREND_DELTA -> Trend.APPROACHING
            delta < -TREND_DELTA -> Trend.RECEDING
            else                 -> Trend.STABLE
        }
    }

    fun reset() {
        phase = Phase.SCAN
        lastGuidanceMs = 0L
        lastSeenMs = 0L
        lastPhrase = ""
        lastElevationDeg = 0f
        lastObstacleMs = 0L
        announcedClasses.clear()
        warnedObstacles.clear()
        distanceWindow.clear()
    }

    private fun friendlyName(cls: String): String = FRIENDLY.getOrDefault(cls, cls)

    companion object {
        private const val AZ_THRESHOLD            = 20f
        private const val ELEV_TILT_THRESHOLD      = 12f
        private const val FOUND_AZ_DEG            = 25f
        private const val NEAR_DEPTH              = 6.5f    // depth-based NAVIGATE→REACH (backup)
        /** Box area thresholds — more reliable than depth at close range.
         *  Geometry: bottle (7cm wide) at 60cm ≈ 0.06, at 35cm ≈ 0.10, at 20cm ≈ 0.16. */
        private const val BOX_AREA_APPROACHING    = 0.04f   // ~arm's length — "getting close" hint
        private const val BOX_AREA_REACH_ENTER    = 0.06f   // NAVIGATE→REACH transition
        private const val BOX_AREA_NEAR           = 0.09f   // "almost touching"
        private const val BOX_AREA_GRAB           = 0.16f   // "right here — grab it!"
        private const val GUIDANCE_RATE_MS        = 1_200L
        private const val REPEAT_SAME_RATE_MS     = 3_000L
        private const val OBSTACLE_RATE_MS        = 5_000L  // don't re-warn same obstacle faster than this
        private const val OBSTACLE_WARN_DEPTH     = 5.5f    // normalised depth — object is close enough to block
        private const val PATH_CONE_DEG           = 28f     // ±28° from heading = "in the path"
        private const val LOST_TIMEOUT_MS         = 3_500L  // NAVIGATE: wait longer before "lost it"
        private const val REACH_LOST_GRACE_MS     = 4_000L  // REACH: if gone within 4s → declare found
        private const val AUTO_EXIT_MS            = 12_000L // longer grace before giving up
        private const val SCAN_ANNOUNCE_RATE_MS   = 2_000L
        private const val SCAN_NUDGE_MS           = 5_000L
        private const val MAX_SCAN_OBJECTS        = 2      // cap orient-announcements to avoid long TTS chains
        private const val TREND_WINDOW            = 4
        private const val TREND_DELTA             = 0.4f

        private val FRIENDLY = mapOf(
            "person" to "person", "chair" to "chair", "couch" to "couch",
            "dining table" to "table", "tv" to "screen", "laptop" to "laptop",
            "cup" to "cup", "bottle" to "bottle", "backpack" to "bag",
            "suitcase" to "suitcase", "refrigerator" to "fridge",
            "cell phone" to "phone", "book" to "book", "clock" to "clock",
        )
    }
}

/**
 * Remembers the last known azimuth + timestamp for each YOLO class so a follow-up
 * "find the bottle" can skip SCAN and start navigating immediately if the object was
 * recently seen (within [MEMORY_TTL_MS]).
 */
object ObjectMemory {
    private const val MEMORY_TTL_MS = 60_000L  // remember for 60 seconds

    data class Entry(val azimuthDeg: Float, val seenAtMs: Long)

    private val store = mutableMapOf<String, Entry>()

    fun update(cls: String, azimuthDeg: Float) {
        store[cls] = Entry(azimuthDeg, System.currentTimeMillis())
    }

    /** Returns the last known direction if seen within [MEMORY_TTL_MS], else null. */
    fun recall(cls: String): Entry? {
        val e = store[cls] ?: return null
        return if (System.currentTimeMillis() - e.seenAtMs < MEMORY_TTL_MS) e else null
    }
}
