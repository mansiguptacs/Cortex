package com.echowalk

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.RadarState

/**
 * Guides the user toward a named YOLO target class.
 *
 * On each [process] call:
 *  1. Scans [RadarState.hazards] for the target class.
 *  2. Computes azimuth-based direction ("Turn left / right / Walk forward").
 *  3. Tracks [distanceM] trend to confirm approach.
 *  4. Declares **found** when the target is close + centred.
 *  5. Handles **lost** state when target leaves frame for > [LOST_TIMEOUT_MS].
 *
 * All guidance is spoken via [AudioOutputManager.speak] and rate-limited to [GUIDANCE_RATE_MS].
 * The safety radar (SpatialAudioEngine) continues running underneath — wall beeps are unaffected.
 *
 * [onFound] is called when the target is reached; [onLost] when it's lost too long.
 */
class FindModeController(
    private val targetClass: String,
    private val audio: AudioOutputManager,
    private val onFound: () -> Unit,
    private val onLost: () -> Unit,
) {
    private var lastGuidanceMs = 0L
    private var lastSeenMs = 0L
    private var lastPhrase = ""

    // Rolling window of the last N distanceM readings to detect approach trend.
    private val distanceWindow = ArrayDeque<Float>(TREND_WINDOW)

    fun process(state: RadarState) {
        if (audio.isSpeaking()) return
        val now = System.currentTimeMillis()

        // Find the best (closest) matching hazard for the target class.
        val target = state.hazards
            .filter { it.cls == targetClass }
            .maxByOrNull { it.distanceM }

        if (target == null) {
            // Target not visible in this frame.
            if (lastSeenMs > 0 && now - lastSeenMs > LOST_TIMEOUT_MS) {
                if (now - lastGuidanceMs > GUIDANCE_RATE_MS) {
                    lastGuidanceMs = now
                    audio.speak("I lost it — turn slowly", flush = false)
                }
                if (now - lastSeenMs > AUTO_EXIT_MS) {
                    audio.speak("${friendlyName(targetClass)} not found. Exiting find mode.", flush = false)
                    onLost()
                }
            }
            return
        }

        lastSeenMs = now

        // Track distance trend.
        if (distanceWindow.size >= TREND_WINDOW) distanceWindow.removeFirst()
        distanceWindow.addLast(target.distanceM)

        // Declare found: target centred + very close.
        if (target.distanceM >= FOUND_DEPTH && kotlin.math.abs(target.azimuthDeg) <= FOUND_AZ_DEG) {
            audio.speak("You've reached the ${friendlyName(targetClass)}.", flush = false)
            onFound()
            return
        }

        if (now - lastGuidanceMs < GUIDANCE_RATE_MS) return

        val phrase = buildGuidancePhrase(target.azimuthDeg, target.distanceM)
        if (phrase == lastPhrase && now - lastGuidanceMs < REPEAT_SAME_RATE_MS) return

        lastGuidanceMs = now
        lastPhrase = phrase
        audio.speak(phrase, flush = false)
    }

    private fun buildGuidancePhrase(azimuthDeg: Float, distanceM: Float): String {
        val direction = when {
            azimuthDeg < -AZ_THRESHOLD -> "Turn left"
            azimuthDeg >  AZ_THRESHOLD -> "Turn right"
            else -> {
                val trend = distanceTrend()
                when {
                    trend == Trend.APPROACHING -> "Getting closer"
                    trend == Trend.RECEDING    -> "Getting farther — try turning"
                    distanceM >= 7f            -> "Walk forward"
                    else                       -> "Walk forward slowly"
                }
            }
        }
        return direction
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
        lastGuidanceMs = 0L
        lastSeenMs = 0L
        lastPhrase = ""
        distanceWindow.clear()
    }

    private fun friendlyName(cls: String): String = FRIENDLY.getOrDefault(cls, cls)

    companion object {
        private const val AZ_THRESHOLD = 13f      // degrees — within this = "forward"
        private const val FOUND_DEPTH = 8.5f      // normalised depth — "you've arrived"
        private const val FOUND_AZ_DEG = 13f      // degrees — centred enough to declare found
        private const val GUIDANCE_RATE_MS = 2_000L
        private const val REPEAT_SAME_RATE_MS = 4_000L
        private const val LOST_TIMEOUT_MS = 2_000L
        private const val AUTO_EXIT_MS = 8_000L
        private const val TREND_WINDOW = 4
        private const val TREND_DELTA = 0.4f

        private val FRIENDLY = mapOf(
            "person" to "person",
            "chair" to "chair",
            "couch" to "couch",
            "dining table" to "table",
            "tv" to "screen",
            "laptop" to "laptop",
            "cup" to "cup",
            "bottle" to "bottle",
            "backpack" to "bag",
            "suitcase" to "suitcase",
            "refrigerator" to "fridge",
            "cell phone" to "phone",
            "book" to "book",
            "clock" to "clock",
            "bowl" to "bowl",
        )
    }
}
