package com.echowalk.places

/**
 * Pure-CPU **fallback** [Embedder] — the "tiny-image" global descriptor. Needs no model file,
 * no NPU, and no Qualcomm libraries, so Team C can prove the full localization/guidance pipeline
 * (M-C0..M-C3) before the real CLIP `.pte` exists (plan §2.4 / Lane 3 fallback).
 *
 * How it works (classic, surprisingly effective baseline for place recognition):
 *  1. **Average-pool** the image down to a [grid]×[grid] thumbnail (so [dim] == grid*grid). This
 *     throws away high-frequency detail and keeps the coarse spatial layout that distinguishes
 *     one viewpoint from another.
 *  2. **Subtract the global mean** (remove the DC component) so a uniform brightness change does
 *     not alter the descriptor — same spot under brighter/dimmer light still matches.
 *  3. **L2-normalize**, which additionally cancels contrast/gain scaling. The result drops
 *     straight into [CosineMatcher] like any real embedding.
 *
 * Properties this gives the pipeline:
 *  - same scene, small viewpoint jitter  -> high cosine,
 *  - different scenes                     -> low cosine,
 *  - a flat/featureless wall              -> zero vector (no DC, nothing distinctive), which the
 *    matcher treats as cosine 0 and the conservative threshold rejects — i.e. it correctly
 *    "stays silent" on non-distinctive views.
 *
 * It is intentionally weak compared to CLIP; it exists to unblock development and demos, and is
 * swapped out for the QNN encoder behind the [Embedder] seam with no downstream changes.
 */
class DownsampleEmbedder(
    private val grid: Int = DEFAULT_GRID,
) : Embedder {

    init {
        require(grid >= 1) { "grid must be >= 1, was $grid" }
    }

    override val dim: Int = grid * grid

    override fun embed(pixels: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "width/height must be > 0, were $width x $height" }
        require(pixels.size == width * height) {
            "pixels.size=${pixels.size} != width*height=${width * height}"
        }

        // 1) Average-pool into grid x grid cells.
        val sums = DoubleArray(dim)
        val counts = IntArray(dim)
        for (y in 0 until height) {
            val gy = y * grid / height // floor mapping, in [0, grid)
            val rowBase = y * width
            val cellRow = gy * grid
            for (x in 0 until width) {
                val gx = x * grid / width
                val cell = cellRow + gx
                sums[cell] += pixels[rowBase + x].toDouble()
                counts[cell]++
            }
        }

        val cells = FloatArray(dim)
        var total = 0.0
        for (i in 0 until dim) {
            val c = counts[i]
            val avg = if (c > 0) sums[i] / c else 0.0
            cells[i] = avg.toFloat()
            total += avg
        }

        // 2) Remove the DC component (brightness invariance).
        val mean = (total / dim).toFloat()
        for (i in 0 until dim) cells[i] -= mean

        // 3) L2-normalize (contrast/gain invariance). Vectors handles the zero-vector case.
        return Vectors.l2normalize(cells)
    }

    companion object {
        /** 16x16 thumbnail -> 256-d descriptor. Small, fast, discriminative enough to demo. */
        const val DEFAULT_GRID = 16
    }
}
