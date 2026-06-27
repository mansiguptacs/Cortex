package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorsTest {

    @Test
    fun `identical vectors have cosine 1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, Vectors.cosine(a, a), 1e-6f)
    }

    @Test
    fun `orthogonal vectors have cosine 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, Vectors.cosine(a, b), 1e-6f)
    }

    @Test
    fun `opposite vectors have cosine minus 1`() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(-1f, -1f)
        assertEquals(-1f, Vectors.cosine(a, b), 1e-6f)
    }

    @Test
    fun `cosine is scale invariant`() {
        val a = floatArrayOf(3f, 4f)
        val b = floatArrayOf(30f, 40f)
        assertEquals(1f, Vectors.cosine(a, b), 1e-6f)
    }

    @Test
    fun `zero vector yields cosine 0 not NaN`() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(1f, 1f)
        assertEquals(0f, Vectors.cosine(a, b), 0f)
    }

    @Test
    fun `l2normalize produces unit length`() {
        val n = Vectors.l2normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, Vectors.norm(n), 1e-6f)
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
    }

    @Test
    fun `same-spot scores higher than different-spot`() {
        // Simulated embeddings: same spot = small perturbation, different spot = unrelated.
        val anchor = floatArrayOf(0.9f, 0.1f, 0.2f, 0.05f)
        val sameSpot = floatArrayOf(0.88f, 0.12f, 0.19f, 0.06f)
        val otherSpot = floatArrayOf(0.1f, 0.85f, -0.3f, 0.4f)
        assertTrue(
            Vectors.cosine(anchor, sameSpot) > Vectors.cosine(anchor, otherSpot),
            "same-spot similarity must exceed different-spot similarity",
        )
    }
}
