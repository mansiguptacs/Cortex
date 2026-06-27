package com.echowalk.teamb.vlm

/**
 * Pure-Kotlin frame sanity checks (no Android deps -> JVM-testable). A blind user can't see when the
 * lens is covered or the room is dark, so we detect it and speak a hint instead of describing black.
 */
object FrameQuality {

    /** Mean perceived luminance (0..255) of ARGB pixels, Rec.601 weights. */
    fun meanLuma(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return (sum / pixels.size).toFloat()
    }

    /** True if the frame is essentially black (covered lens / dark room). */
    fun isTooDark(pixels: IntArray, threshold: Float = 24f): Boolean = meanLuma(pixels) < threshold
}
