package com.echowalk.teama

/** What kind of thing the radar found, which drives the audio timbre. */
enum class HazardKind { WALL, OBSTACLE, DROPOFF }

/** A single detected hazard with its estimated position. */
data class Hazard(
    val cls: String,        // e.g. "chair", "person"; "" for raw depth-only
    val distanceM: Float,   // relative/estimated meters (depth is relative, calibrate thresholds)
    val azimuthDeg: Float,  // negative = left, 0 = center, positive = right
    val kind: HazardKind,
)

/** Snapshot of the scene for the audio/haptic engine. */
data class RadarState(
    val zoneNearestM: FloatArray, // [left, center, right] nearest distances
    val hazards: List<Hazard>,
    val tsMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadarState) return false
        return tsMs == other.tsMs &&
            hazards == other.hazards &&
            zoneNearestM.contentEquals(other.zoneNearestM)
    }

    override fun hashCode(): Int =
        31 * (31 * zoneNearestM.contentHashCode() + hazards.hashCode()) + tsMs.hashCode()
}

/**
 * Team A's interface. Continuous obstacle-avoidance loop.
 * FROZEN — coordinate before changing the signature.
 */
interface SafetyRadar {
    fun start()
    fun stop()
    fun observe(listener: (RadarState) -> Unit)
}
