package com.echowalk.teamb.narration

/**
 * Decides *when* ambient mode should speak — the heart of "don't overwhelm the user".
 *
 * Empathy rules encoded here:
 *  - **Be sure first**: ignore low-confidence frames; require a smoothed (EMA) confidence.
 *  - **Be stable**: the same scene must hold for a few cycles, so a passing glimpse of a wall
 *    doesn't trigger a wrong announcement.
 *  - **Only speak changes**: never repeat the current scene; announce only when it actually changes.
 *  - **Respect their ears**: enforce a minimum gap between announcements (a speech budget).
 *
 * Pure Kotlin + injectable clock -> fully JVM-testable with no device.
 */
class SceneStabilizer(
    private val minConf: Float = 0.18f,       // below this, the frame is ignored entirely
    private val announceConf: Float = 0.30f,  // smoothed confidence required to speak
    private val stableCycles: Int = 2,        // consecutive agreeing frames before speaking
    private val minIntervalMs: Long = 4_000L, // speech budget between announcements
    private val emaAlpha: Float = 0.5f,       // smoothing weight on the newest frame
) {
    private var candidate: String? = null
    private var streak = 0
    private var ema = 0f
    private var lastAnnounced: String? = null
    // Start one budget in the past so the first valid scene can announce (and avoid Long underflow).
    private var lastAnnouncedMs = -minIntervalMs

    sealed interface Decision {
        data object Silent : Decision
        data class Announce(val term: String, val confidence: Float) : Decision
    }

    /**
     * Feed the current top scene guess. Returns [Decision.Announce] only when all gates pass.
     * Pass [term] = null (or empty) when nothing was confidently detected this cycle.
     */
    fun observe(term: String?, prob: Float, nowMs: Long): Decision {
        if (term.isNullOrBlank() || prob < minConf) {
            candidate = null
            streak = 0
            ema = 0f
            return Decision.Silent
        }
        if (term == candidate) {
            streak++
            ema = emaAlpha * prob + (1 - emaAlpha) * ema
        } else {
            candidate = term
            streak = 1
            ema = prob
        }

        val stable = streak >= stableCycles && ema >= announceConf
        val isNew = term != lastAnnounced
        val budgetOk = nowMs - lastAnnouncedMs >= minIntervalMs
        return if (stable && isNew && budgetOk) {
            lastAnnounced = term
            lastAnnouncedMs = nowMs
            Decision.Announce(term, ema)
        } else {
            Decision.Silent
        }
    }

    /** Record that [term] was just spoken (e.g. by a manual describe) so ambient won't echo it. */
    fun noteAnnounced(term: String?, nowMs: Long) {
        if (!term.isNullOrBlank()) {
            lastAnnounced = term
            lastAnnouncedMs = nowMs
        }
    }

    /** Forget history (e.g. when ambient mode is toggled off/on). */
    fun reset() {
        candidate = null
        streak = 0
        ema = 0f
        lastAnnounced = null
        lastAnnouncedMs = -minIntervalMs
    }
}
