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

    /**
     * Focus measure = variance of the Laplacian over luminance. High for sharp frames, low for
     * motion-blurred ones. In ambient mode we skip blurry frames (a walking user produces blur, and
     * classifying blur produces garbage) and only classify when the camera momentarily steadies.
     * [pixels] is a small downscaled ARGB grid (e.g. 64x64) — cheap to scan.
     */
    fun sharpness(pixels: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3 || pixels.size < w * h) return 0.0
        val luma = DoubleArray(w * h) { i ->
            val p = pixels[i]
            0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n) - mean * mean
    }

    /** True if the frame is too blurry to classify reliably (below [threshold] focus measure). */
    fun isBlurry(pixels: IntArray, w: Int, h: Int, threshold: Double = 8.0): Boolean =
        sharpness(pixels, w, h) < threshold
}
