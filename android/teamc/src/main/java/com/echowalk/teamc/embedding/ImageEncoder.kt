package com.echowalk.teamc.embedding

import android.graphics.Bitmap
import com.echowalk.shared.EtModule
import com.echowalk.shared.Frame
import kotlin.math.sqrt

/**
 * Turns a [Frame] into an L2-normalized embedding for place recognition.
 *
 * Two interchangeable paths (same idea as the JVM `core` module's `Embedder` seam):
 *  - **NPU path** (preferred): a CLIP/MobileCLIP image encoder `.pte` run via the shared
 *    [EtModule]. Used when an [etModule] is supplied. (M-C0 deliverable — preprocessing TODO.)
 *  - **CPU fallback** (no `.pte`, no QNN libs): a "tiny-image" descriptor — average-pool the
 *    grayscale frame to [grid]x[grid], remove the DC component (brightness invariance), then
 *    L2-normalize. This mirrors `com.echowalk.places.DownsampleEmbedder` in the core module and
 *    lets the whole app enroll/localize/guide BEFORE the model exists (plan §2.4).
 *
 * Construct with `ImageEncoder()` for the fallback, or `ImageEncoder(etModule)` once the `.pte`
 * is bundled. Nothing downstream changes between the two.
 */
class ImageEncoder(
    private val etModule: EtModule? = null,
    private val grid: Int = DEFAULT_GRID,
) {

    /** Dimensionality of the produced embeddings. */
    val dim: Int get() = if (etModule != null) MODEL_DIM else grid * grid

    fun encode(frame: Frame): FloatArray =
        if (etModule != null) encodeWithModel(frame) else fallbackEncode(frame)

    // ---- CPU fallback (no model needed) ------------------------------------

    private fun fallbackEncode(frame: Frame): FloatArray {
        val gray = toGrayscale(frame.rgb)
        return downsampleDescriptor(gray, frame.rgb.width, frame.rgb.height, grid)
    }

    private fun toGrayscale(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma, normalized to [0,1].
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return out
    }

    // ---- NPU path (needs the .pte) -----------------------------------------

    private fun encodeWithModel(frame: Frame): FloatArray {
        // TODO(Team C, M-C0): resize frame.rgb to the encoder input (e.g. 224x224), normalize to
        // the model's mean/std, build the CHW float tensor, call etModule.forward(...), then
        // L2-normalize the first output. Until then the fallback above keeps the app working.
        throw NotImplementedError("real CLIP encoder path not wired yet; use ImageEncoder() fallback")
    }

    companion object {
        const val DEFAULT_GRID = 16
        const val MODEL_DIM = 512 // OpenAI-CLIP ViT-B/16 image embedding size

        /**
         * Average-pool grayscale [pixels] to [grid]x[grid], remove DC, L2-normalize.
         * Kept here (not shared with core) because the Android module is a separate Gradle build;
         * the algorithm must stay in sync with `places.DownsampleEmbedder`.
         */
        fun downsampleDescriptor(pixels: FloatArray, width: Int, height: Int, grid: Int): FloatArray {
            val dim = grid * grid
            val sums = DoubleArray(dim)
            val counts = IntArray(dim)
            for (y in 0 until height) {
                val gy = y * grid / height
                val rowBase = y * width
                val cellRow = gy * grid
                for (x in 0 until width) {
                    val cell = cellRow + (x * grid / width)
                    sums[cell] += pixels[rowBase + x].toDouble()
                    counts[cell]++
                }
            }
            val cells = FloatArray(dim)
            var total = 0.0
            for (i in 0 until dim) {
                val avg = if (counts[i] > 0) sums[i] / counts[i] else 0.0
                cells[i] = avg.toFloat()
                total += avg
            }
            val mean = (total / dim).toFloat()
            var sq = 0f
            for (i in 0 until dim) {
                cells[i] -= mean
                sq += cells[i] * cells[i]
            }
            val norm = sqrt(sq)
            if (norm > 0f) for (i in 0 until dim) cells[i] /= norm
            return cells
        }
    }
}
