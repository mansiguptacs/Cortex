package com.echowalk.places

/**
 * The swappable encoder seam for place recognition.
 *
 * Production path: a CLIP/MobileCLIP image encoder running on the NPU via the shared `EtModule`.
 * Fallback path: [DownsampleEmbedder], a pure-CPU "tiny-image" descriptor that needs **no `.pte`
 * and no QNN libraries** — so the entire enroll → localize → guide pipeline is runnable and
 * testable on a plain JVM today. Because both implementations satisfy this one interface and emit
 * L2-normalized vectors, swapping in the real QNN encoder later is a one-line change with nothing
 * downstream to touch (the plan's §2.4 unblock-yourself strategy).
 *
 * Input is a single-channel (grayscale) image in row-major order, intensities in any range — the
 * embedder is responsible for its own normalization. Keeping the contract Android-free (plain
 * `FloatArray`, not a `Frame`/`Bitmap`) is what lets it live in the JVM core and be unit-tested
 * with synthetic images.
 */
interface Embedder {
    /** Dimensionality of the vectors produced by [embed]. */
    val dim: Int

    /**
     * Encode a grayscale image into an L2-normalized embedding of length [dim].
     *
     * @param pixels row-major intensities, size must equal `width * height`.
     * @param width  image width in pixels (> 0).
     * @param height image height in pixels (> 0).
     */
    fun embed(pixels: FloatArray, width: Int, height: Int): FloatArray
}
