package com.echowalk.teamb.vlm

import android.graphics.Bitmap

/**
 * Turns a camera [Bitmap] into the float tensor SmolVLM's vision encoder expects.
 *
 * PLACEHOLDER I/O SPEC (U-Step 4) — align these constants with Jainil's export at U-Step 5:
 *   - layout: NCHW, shape [1, 3, SIZE, SIZE]
 *   - SIZE:   384 (SmolVLM / SigLIP image size)
 *   - range:  per-channel (x/255 - MEAN) / STD  (SigLIP uses 0.5/0.5)
 *
 * [pixelsToCHW] is intentionally pure (no Android types) so it's covered by fast JVM unit tests.
 */
object VlmPreprocess {
    const val SIZE = 384
    val INPUT_SHAPE = intArrayOf(1, 3, SIZE, SIZE)

    /** SigLIP / SmolVLM normalization. */
    val SIGLIP_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    val SIGLIP_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    /** Standard ImageNet normalization (most torchvision classifiers). */
    val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Scale a bitmap to [size]x[size] and produce a normalized CHW float array. */
    fun toCHW(
        bitmap: Bitmap,
        size: Int = SIZE,
        mean: FloatArray = SIGLIP_MEAN,
        std: FloatArray = SIGLIP_STD,
    ): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        return pixelsToCHW(pixels, size, size, mean, std)
    }

    /** Pure: ARGB int pixels (row-major) -> normalized CHW float array of length 3*w*h. */
    fun pixelsToCHW(
        pixels: IntArray,
        w: Int,
        h: Int,
        mean: FloatArray = SIGLIP_MEAN,
        std: FloatArray = SIGLIP_STD,
    ): FloatArray {
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - mean[0]) / std[0]
            out[plane + i] = (g - mean[1]) / std[1]
            out[2 * plane + i] = (b - mean[2]) / std[2]
        }
        return out
    }
}
