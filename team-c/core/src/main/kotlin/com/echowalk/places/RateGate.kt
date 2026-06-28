package com.echowalk.places

/**
 * Minimal fixed-interval gate used to throttle the place-recognition encoder to ~1-2 Hz.
 *
 * The safety radar (Team A) owns the NPU at a high rate; the familiar-places loop must take only
 * occasional slices so it doesn't starve the radar (a hard constraint from the plan: "never run
 * every frame"). The Android adapter calls [allow] with each incoming frame timestamp and only
 * runs the encoder when it returns true.
 *
 * Pure and clock-injected (no `System.currentTimeMillis()` inside), so the throttle is fully
 * unit-testable on the JVM with a synthetic clock.
 */
class RateGate(private val minIntervalMs: Long) {

    init {
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0, was $minIntervalMs" }
    }

    private var lastMs: Long? = null

    /**
     * Returns true (and arms the next interval from [nowMs]) when at least [minIntervalMs] has
     * elapsed since the last accepted call; otherwise returns false. The very first call always
     * passes.
     */
    fun allow(nowMs: Long): Boolean {
        val prev = lastMs
        if (prev == null || nowMs - prev >= minIntervalMs) {
            lastMs = nowMs
            return true
        }
        return false
    }

    /** Forget the last pass time, e.g. when (re)entering the localization loop. */
    fun reset() {
        lastMs = null
    }

    companion object {
        /** Build a gate for a target rate in Hz, e.g. `hz(2.0)` -> one pass every 500 ms. */
        fun hz(rate: Double): RateGate {
            require(rate > 0) { "rate must be > 0, was $rate" }
            return RateGate((1000.0 / rate).toLong())
        }
    }
}
