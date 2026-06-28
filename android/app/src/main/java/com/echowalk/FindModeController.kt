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

    fun process(state: RadarState) {
        if (audio.isSpeaking()) return
        val now = System.currentTimeMillis()

        val target = state.hazards
            .filter { it.cls == targetClass }
            .maxByOrNull { it.distanceM }

        // Save last known azimuth for re-find memory
        if (target != null) ObjectMemory.update(targetClass, target.azimuthDeg)

        return when (phase) {
            Phase.SCAN     -> processScan(state, target, now)
            Phase.NAVIGATE -> processNavigate(target, now)
            Phase.REACH    -> processReach(target, now)
        }
    }

    // During SCAN: announce every new visible object; switch to NAVIGATE when target appears.
    private fun processScan(state: RadarState, target: com.echowalk.teama.Hazard?, now: Long) {
        if (target != null) {
            phase = Phase.NAVIGATE
            lastSeenMs = now
            audio.speak("Found it — ${friendlyName(targetClass)} ${directionPhrase(target.azimuthDeg)}. I'll guide you.", flush = false)
            lastGuidanceMs = now
            return
        }
        if (now - lastGuidanceMs < SCAN_ANNOUNCE_RATE_MS) return
        val newClass = state.hazards
            .filterNot { it.cls == targetClass || it.cls in announcedClasses }
            .maxByOrNull { it.distanceM }
        if (newClass != null) {
            announcedClasses.add(newClass.cls)
            audio.speak("I see a ${friendlyName(newClass.cls)} ${directionPhrase(newClass.azimuthDeg)}", flush = false)
            lastGuidanceMs = now
        } else if (now - lastGuidanceMs > SCAN_NUDGE_MS) {
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
        if (distanceWindow.size >= TREND_WINDOW) distanceWindow.removeFirst()
        distanceWindow.addLast(target.distanceM)

        // Transition to REACH phase when target is close
        if (target.distanceM >= NEAR_DEPTH && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG) {
            phase = Phase.REACH
            audio.speak("Almost there — walk forward slowly.", flush = false)
            lastGuidanceMs = now
            return
        }

        if (now - lastGuidanceMs < GUIDANCE_RATE_MS) return
        val phrase = buildNavigationPhrase(target.azimuthDeg, target.distanceM)
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
                        "The ${friendlyName(targetClass)} is very close — you can reach it now."
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

        // Box-area proximity check: when the object fills enough of the frame → grab it.
        if (target.boxArea >= BOX_AREA_GRAB && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG) {
            audio.speak("You're very close — reach out and grab the ${friendlyName(targetClass)}!", flush = false)
            onFound()
            return
        }
        // Intermediate close hint (box approaching grab size)
        if (target.boxArea >= BOX_AREA_NEAR && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG
            && now - lastGuidanceMs > GUIDANCE_RATE_MS) {
            audio.speak("Almost there — the ${friendlyName(targetClass)} is right in front of you.", flush = false)
            lastGuidanceMs = now
            lastPhrase = "almost"
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

    private fun buildNavigationPhrase(azimuthDeg: Float, distanceM: Float): String {
        return when {
            azimuthDeg < -AZ_THRESHOLD -> "Turn left"
            azimuthDeg >  AZ_THRESHOLD -> "Turn right"
            else -> {
                val trend = distanceTrend()
                when {
                    trend == Trend.APPROACHING -> "Getting closer — keep going"
                    trend == Trend.RECEDING    -> "Getting farther — try turning"
                    distanceM >= 6f            -> "Walk forward"
                    else                       -> "Walk forward slowly"
                }
            }
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
        announcedClasses.clear()
        distanceWindow.clear()
    }

    private fun friendlyName(cls: String): String = FRIENDLY.getOrDefault(cls, cls)

    companion object {
        private const val AZ_THRESHOLD            = 20f
        private const val ELEV_TILT_THRESHOLD      = 12f     // degrees — tilt guidance threshold
        private const val FOUND_AZ_DEG            = 25f
        private const val NEAR_DEPTH              = 6.5f    // transition NAVIGATE→REACH
        private const val REACH_DEPTH             = 7.5f    // depth-based arrived threshold (backup)
        private const val BOX_AREA_NEAR           = 0.12f   // ~35% width box → "almost there"
        private const val BOX_AREA_GRAB           = 0.22f   // ~47% width box → "reach out and grab it"
        private const val GUIDANCE_RATE_MS        = 2_000L
        private const val REPEAT_SAME_RATE_MS     = 4_000L
        private const val LOST_TIMEOUT_MS         = 3_500L  // NAVIGATE: wait longer before "lost it"
        private const val REACH_LOST_GRACE_MS     = 4_000L  // REACH: if gone within 4s → declare found
        private const val AUTO_EXIT_MS            = 12_000L // longer grace before giving up
        private const val SCAN_ANNOUNCE_RATE_MS   = 2_000L
        private const val SCAN_NUDGE_MS           = 5_000L
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
