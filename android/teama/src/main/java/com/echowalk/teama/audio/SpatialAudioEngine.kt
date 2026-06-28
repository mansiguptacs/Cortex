package com.echowalk.teama.audio

import com.echowalk.shared.AudioOutputManager
import com.echowalk.teama.RadarState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proximity **haptic** guidance for Team A's safety radar.
 *
 * Replaces the old continuous audio "beeps" with a calm, eyes-free *tactile* channel so the user
 * isn't overwhelmed by sound. The closer the nearest obstacle, the faster and stronger the phone
 * pulses — a "geiger-counter" feel that intuitively guides the user as they approach something.
 * Speech (Team A semantic warnings + Team B scene description) is now the only audio output.
 *
 * The pulse loop runs on its own thread, *decoupled from the radar's inference rate*: [render]
 * just records the latest proximity, while a dedicated loop emits pulses at the proximity-scaled
 * cadence. This keeps the haptics responsive (fast pulses when close) even when depth+YOLO
 * inference is only a few frames per second.
 *
 * Mapping (relative depth from Depth-Anything-V2; higher value = closer):
 *   - below [NEAR_START]            → silent (nothing close; don't buzz)
 *   - [NEAR_START] … [NEAR_FULL]    → interval [PULSE_SLOW_MS]→[PULSE_FAST_MS],
 *                                     amplitude [AMP_MIN]→[AMP_MAX], duration [DUR_MIN_MS]→[DUR_MAX_MS]
 *
 * The class name + constructor are intentionally unchanged so the existing radar wiring
 * ([com.echowalk.teama.SafetyRadarController], ModeManager, the Team A harness) needs no edits.
 * Hosts should call [release] from onDestroy to stop the pulse thread.
 */
class SpatialAudioEngine(
    private val audio: AudioOutputManager,
) {
    @Volatile
    private var latestRel = Float.NEGATIVE_INFINITY

    @Volatile
    private var latestRelMs = 0L

    private val running = AtomicBoolean(true)

    private val pulseThread = Thread(::pulseLoop, "radar-haptic").apply {
        isDaemon = true
        start()
    }

    /** Called once per radar tick: record how close the nearest obstacle is right now. */
    fun render(state: RadarState) {
        latestRel = nearestProximity(state)
        latestRelMs = System.currentTimeMillis()
    }

    /** Emits proximity-scaled vibration pulses until [release]; rate is independent of radar FPS. */
    private fun pulseLoop() {
        while (running.get()) {
            val now = System.currentTimeMillis()
            val rel = latestRel
            val fresh = now - latestRelMs < STALE_MS // stop buzzing if radar paused/stalled
            if (fresh && rel >= NEAR_START) {
                val t = ((rel - NEAR_START) / (NEAR_FULL - NEAR_START)).coerceIn(0f, 1f)
                val amplitude = lerp(AMP_MIN, AMP_MAX, t).toInt().coerceIn(1, 255)
                val durationMs = lerp(DUR_MIN_MS, DUR_MAX_MS, t).toLong()
                val intervalMs = lerp(PULSE_SLOW_MS, PULSE_FAST_MS, t).toLong()
                audio.haptic(durationMs, amplitude)
                sleepQuietly(intervalMs)
            } else {
                sleepQuietly(IDLE_POLL_MS)
            }
        }
    }

    /**
     * The closest signal in the frame. Prefers semantic YOLO hazards (a real object you're nearing);
     * lets the raw depth zones contribute only when one zone is *clearly* the nearest (e.g. a wall
     * straight ahead). This dominance gate prevents constant buzzing in cluttered scenes where the
     * normalized depth always has a "near" value somewhere.
     */
    private fun nearestProximity(state: RadarState): Float {
        val hazardMax = state.hazards.maxOfOrNull { it.distanceM } ?: Float.NEGATIVE_INFINITY

        val finite = state.zoneNearestM.filter { it.isFinite() }
        val zoneContribution = if (finite.size >= 2) {
            val zMax = finite.max()
            val zMin = finite.min()
            if (zMax - zMin >= ZONE_DOMINANCE_DELTA && zMax >= ZONE_MIN_TO_PULSE) zMax
            else Float.NEGATIVE_INFINITY
        } else {
            Float.NEGATIVE_INFINITY
        }

        return maxOf(hazardMax, zoneContribution)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Stop the pulse thread. Safe to call multiple times. */
    fun release() {
        running.set(false)
        pulseThread.interrupt()
    }

    private companion object {
        // Proximity window (relative depth). Matches the radar's urgency thresholds (5.0 / 8.5).
        const val NEAR_START = 5.0f      // below this: nothing close → no haptic
        const val NEAR_FULL = 9.0f       // at/above this: fastest, strongest pulses

        const val PULSE_SLOW_MS = 700f   // gentle, sparse taps when an object first comes into range
        const val PULSE_FAST_MS = 110f   // rapid, urgent buzzing when very close
        const val AMP_MIN = 60f          // light tap (1..255)
        const val AMP_MAX = 255f         // full-strength buzz
        const val DUR_MIN_MS = 18f
        const val DUR_MAX_MS = 45f

        const val IDLE_POLL_MS = 150L    // how often to re-check proximity while nothing is close
        const val STALE_MS = 5_000L      // ignore readings older than this (radar paused during describe)

        // Depth-zone dominance gate (mirrors the previous audio engine) so zones don't buzz nonstop.
        const val ZONE_DOMINANCE_DELTA = 2.5f
        const val ZONE_MIN_TO_PULSE = 6.5f
    }
}
