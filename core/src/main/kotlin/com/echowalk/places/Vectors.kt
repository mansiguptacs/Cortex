package com.echowalk.places

import kotlin.math.sqrt

/**
 * Vector math for embedding similarity. Embeddings produced by the CLIP image encoder are
 * compared with cosine similarity. We keep these as plain functions so the whole localization
 * core is testable on the JVM with synthetic vectors (Lane 1, zero device/NPU dependency).
 */
object Vectors {

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dim mismatch: ${a.size} != ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun norm(a: FloatArray): Float = sqrt(dot(a, a))

    /** Returns a new L2-normalized copy. A zero vector is returned unchanged (norm 0). */
    fun l2normalize(a: FloatArray): FloatArray {
        val n = norm(a)
        if (n == 0f) return a.copyOf()
        val out = FloatArray(a.size)
        for (i in a.indices) out[i] = a[i] / n
        return out
    }

    /**
     * Cosine similarity in [-1, 1]. Safe for un-normalized inputs (divides by magnitudes).
     * If either vector is all-zero, similarity is 0.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = norm(a)
        val nb = norm(b)
        if (na == 0f || nb == 0f) return 0f
        return dot(a, b) / (na * nb)
    }
}
