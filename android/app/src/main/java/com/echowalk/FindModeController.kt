package com.echowalk

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.RadarState

/**
 * Guides the user toward a named YOLO target class.
 *
 * Two phases:
 *  1. **SCAN** — target not yet seen. User is asked to rotate slowly. Every new object class
 *     visible is announced ("I see a chair on your left"). When the target first appears, switches
 *     to NAVIGATE.
 *  2. **NAVIGATE** — target is visible. Gives azimuth-based directions + distance trend.
 *     Declares FOUND when target is close + centred. Handles LOST if target leaves frame.
 *
 * During Find mode the [VoiceWarningEngine] is in safetyOnlyMode so object chatter is muted.
 */
class FindModeController(
    private val targetClass: String,
    private val audio: AudioOutputManager,
    private val onFound: () -> Unit,
    private val onLost: () -> Unit,
) {
    private enum class Phase { SCAN, NAVIGATE }

    private var phase = Phase.SCAN
    private var lastGuidanceMs = 0L
    private var lastSeenMs = 0L
    private var lastPhrase = ""
    private val announcedClasses = mutableSetOf<String>() // classes announced during SCAN
    private val distanceWindow = ArrayDeque<Float>(TREND_WINDOW)

    fun process(state: RadarState) {
        if (audio.isSpeaking()) return
        val now = System.currentTimeMillis()

        val target = state.hazards
            .filter { it.cls == targetClass }
            .maxByOrNull { it.distanceM }

        return when (phase) {
            Phase.SCAN -> processScan(state, target, now)
            Phase.NAVIGATE -> processNavigate(target, now)
        }
    }

    // During SCAN: announce every new object class seen, switch to NAVIGATE when target appears.
    private fun processScan(state: RadarState, target: com.echowalk.teama.Hazard?, now: Long) {
        if (target != null) {
            // Target spotted — switch to navigation.
            phase = Phase.NAVIGATE
            lastSeenMs = now
            audio.speak("Found it — ${friendlyName(targetClass)} ahead. I'll guide you.", flush = false)
            lastGuidanceMs = now
            return
        }
        // Announce any new visible objects to help orient the user.
        if (now - lastGuidanceMs < SCAN_ANNOUNCE_RATE_MS) return
        val newClass = state.hazards
            .filterNot { it.cls == targetClass || it.cls in announcedClasses }
            .maxByOrNull { it.distanceM }
        if (newClass != null) {
            announcedClasses.add(newClass.cls)
            val dir = directionPhrase(newClass.azimuthDeg)
            audio.speak("I see a ${friendlyName(newClass.cls)} $dir", flush = false)
            lastGuidanceMs = now
        } else if (now - lastGuidanceMs > SCAN_NUDGE_MS) {
            // Nothing new visible — nudge to keep rotating.
            audio.speak("Keep turning slowly", flush = false)
            lastGuidanceMs = now
        }
    }

    // During NAVIGATE: give directional guidance toward the target.
    private fun processNavigate(target: com.echowalk.teama.Hazard?, now: Long) {
        if (target == null) {
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
            return
        }

        lastSeenMs = now
        if (distanceWindow.size >= TREND_WINDOW) distanceWindow.removeFirst()
        distanceWindow.addLast(target.distanceM)

        // Declare found: target centred + close enough (lowered threshold to match real readings).
        if (target.distanceM >= FOUND_DEPTH && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG) {
            audio.speak("You've reached the ${friendlyName(targetClass)}.", flush = false)
            onFound()
            return
        }

        if (now - lastGuidanceMs < GUIDANCE_RATE_MS) return

        val phrase = buildNavigationPhrase(target.azimuthDeg, target.distanceM)
        if (phrase == lastPhrase && now - lastGuidanceMs < REPEAT_SAME_RATE_MS) return

        lastGuidanceMs = now
        lastPhrase = phrase
        audio.speak(phrase, flush = false)
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
        announcedClasses.clear()
        distanceWindow.clear()
    }

    private fun friendlyName(cls: String): String = FRIENDLY.getOrDefault(cls, cls)

    companion object {
        private const val AZ_THRESHOLD    = 20f      // degrees — wider zone = "forward"
        private const val FOUND_DEPTH     = 6.5f     // lowered from 8.5; typical close reading is 6-7
        private const val FOUND_AZ_DEG    = 25f      // wider: user doesn't need to be perfectly centred
        private const val GUIDANCE_RATE_MS         = 2_000L
        private const val REPEAT_SAME_RATE_MS      = 4_000L
        private const val LOST_TIMEOUT_MS          = 2_500L
        private const val AUTO_EXIT_MS             = 9_000L
        private const val SCAN_ANNOUNCE_RATE_MS    = 2_000L // how often to announce scan objects
        private const val SCAN_NUDGE_MS            = 5_000L // nudge "keep turning" after this silence
        private const val TREND_WINDOW  = 4
        private const val TREND_DELTA   = 0.4f

        private val FRIENDLY = mapOf(
            "person" to "person", "chair" to "chair", "couch" to "couch",
            "dining table" to "table", "tv" to "screen", "laptop" to "laptop",
            "cup" to "cup", "bottle" to "bottle", "backpack" to "bag",
            "suitcase" to "suitcase", "refrigerator" to "fridge",
            "cell phone" to "phone", "book" to "book", "clock" to "clock",
        )
    }
}
