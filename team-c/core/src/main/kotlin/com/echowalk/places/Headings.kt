package com.echowalk.places

/** Compass-heading helpers (degrees, 0..360, clockwise). */
object Headings {

    /** Normalize any angle into [0, 360). */
    fun normalize360(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    /**
     * Signed turn needed to go from heading [from] to heading [to], in (-180, 180].
     * Positive = turn right (clockwise), negative = turn left. Null if either heading is unknown.
     */
    fun relativeTurn(from: Float?, to: Float?): Float? {
        if (from == null || to == null) return null
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Coarse human-friendly direction from a signed relative turn. */
    fun describeTurn(relativeTurnDeg: Float, straightThresholdDeg: Float = 30f): String = when {
        relativeTurnDeg > straightThresholdDeg -> "right"
        relativeTurnDeg < -straightThresholdDeg -> "left"
        else -> "straight"
    }
}
