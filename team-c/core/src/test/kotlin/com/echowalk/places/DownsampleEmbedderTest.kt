package com.echowalk.places

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownsampleEmbedderTest {

    private val embedder = DownsampleEmbedder(grid = 16)

    // ---- synthetic image generators ----------------------------------------

    private fun image(w: Int, h: Int, f: (x: Int, y: Int) -> Float): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = f(x, y)
        return out
    }

    private fun horizontalGradient(w: Int, h: Int, shift: Int = 0) =
        image(w, h) { x, _ -> ((x + shift).coerceIn(0, w - 1)).toFloat() / (w - 1) }

    private fun verticalGradient(w: Int, h: Int) =
        image(w, h) { _, y -> y.toFloat() / (h - 1) }

    private fun checkerboard(w: Int, h: Int, cell: Int = 8) =
        image(w, h) { x, y -> if (((x / cell) + (y / cell)) % 2 == 0) 1f else 0f }

    // ---- tests --------------------------------------------------------------

    @Test
    fun `dim is grid squared and output length matches`() {
        assertEquals(256, embedder.dim)
        val emb = embedder.embed(horizontalGradient(64, 64), 64, 64)
        assertEquals(256, emb.size)
    }

    @Test
    fun `output is L2-normalized`() {
        val emb = embedder.embed(checkerboard(64, 64), 64, 64)
        assertEquals(1f, Vectors.norm(emb), 1e-4f)
    }

    @Test
    fun `is deterministic`() {
        val img = checkerboard(48, 48)
        val a = embedder.embed(img, 48, 48)
        val b = embedder.embed(img, 48, 48)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun `same scene with small viewpoint shift scores high`() {
        val base = embedder.embed(horizontalGradient(128, 128), 128, 128)
        val shifted = embedder.embed(horizontalGradient(128, 128, shift = 3), 128, 128)
        assertTrue(Vectors.cosine(base, shifted) > 0.9f, "small shift should stay very similar")
    }

    @Test
    fun `different scenes score low`() {
        val h = embedder.embed(horizontalGradient(128, 128), 128, 128)
        val v = embedder.embed(verticalGradient(128, 128), 128, 128)
        assertTrue(Vectors.cosine(h, v) < 0.3f, "orthogonal layouts should be dissimilar")
    }

    @Test
    fun `invariant to a uniform brightness shift`() {
        val img = checkerboard(64, 64)
        val brighter = FloatArray(img.size) { img[it] + 0.5f } // add constant illumination
        val a = embedder.embed(img, 64, 64)
        val b = embedder.embed(brighter, 64, 64)
        assertTrue(Vectors.cosine(a, b) > 0.999f, "DC removal should cancel brightness")
    }

    @Test
    fun `invariant to contrast or gain scaling`() {
        val img = checkerboard(64, 64)
        val scaled = FloatArray(img.size) { img[it] * 3.7f }
        val a = embedder.embed(img, 64, 64)
        val b = embedder.embed(scaled, 64, 64)
        assertTrue(Vectors.cosine(a, b) > 0.999f, "L2-normalize should cancel gain")
    }

    @Test
    fun `flat featureless image yields a zero vector`() {
        val flat = FloatArray(64 * 64) { 0.5f }
        val emb = embedder.embed(flat, 64, 64)
        assertEquals(0f, Vectors.norm(emb), 0f, "no DC component -> nothing distinctive")
    }

    @Test
    fun `handles dimensions not divisible by the grid`() {
        // 70x50 with grid 16 is fine; just must not throw and must stay normalized.
        val emb = embedder.embed(horizontalGradient(70, 50), 70, 50)
        assertEquals(256, emb.size)
        assertEquals(1f, Vectors.norm(emb), 1e-4f)
    }

    @Test
    fun `rejects mismatched pixel buffer`() {
        assertFailsWith<IllegalArgumentException> { embedder.embed(FloatArray(10), 4, 4) }
    }

    @Test
    fun `rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> { embedder.embed(FloatArray(0), 0, 4) }
    }

    @Test
    fun `grid of one collapses to a single DC-removed cell`() {
        // grid=1 -> the single cell equals the mean, DC removal zeroes it.
        val one = DownsampleEmbedder(grid = 1)
        assertEquals(1, one.dim)
        val emb = one.embed(checkerboard(32, 32), 32, 32)
        assertEquals(0f, abs(emb[0]), 0f)
    }
}
