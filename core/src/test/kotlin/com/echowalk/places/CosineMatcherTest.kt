package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CosineMatcherTest {

    private fun lm(id: Long, label: String, order: Int, vararg emb: FloatArray) =
        Landmark(id = id, placeId = "p1", label = label, embeddings = emb.toList(), orderIndex = order)

    private val entrance = lm(1, "entrance", 0, floatArrayOf(1f, 0f, 0f))
    private val desk = lm(2, "desk", 1, floatArrayOf(0f, 1f, 0f))
    private val fridge = lm(3, "fridge", 2, floatArrayOf(0f, 0f, 1f))
    private val all = listOf(entrance, desk, fridge)

    @Test
    fun `picks the nearest landmark`() {
        val m = CosineMatcher(threshold = 0.5f)
        val query = floatArrayOf(0.05f, 0.98f, 0.02f) // close to desk
        val best = m.best(query, all)
        assertNotNull(best)
        assertEquals("desk", best.label)
    }

    @Test
    fun `stays silent when below threshold`() {
        val m = CosineMatcher(threshold = 0.9f)
        // Equidistant-ish query, no strong match.
        val query = floatArrayOf(0.58f, 0.58f, 0.58f)
        assertNull(m.best(query, all))
    }

    @Test
    fun `uses max over multiple embeddings per landmark`() {
        val m = CosineMatcher(threshold = 0.9f)
        // desk's first embedding points away from the query, but a second enrolled embedding
        // points exactly at it. Max-over-embeddings must let desk win.
        val deskMulti = Landmark(
            id = 2, placeId = "p1", label = "desk", orderIndex = 1,
            embeddings = listOf(
                floatArrayOf(0f, 1f, 0f, 0f),
                floatArrayOf(0f, 0f, 0f, 1f), // exact match for the query below
            ),
        )
        val entrance4 = Landmark(1, "p1", "entrance", listOf(floatArrayOf(1f, 0f, 0f, 0f)), null, 0)
        val fridge4 = Landmark(3, "p1", "fridge", listOf(floatArrayOf(0f, 0f, 1f, 0f)), null, 2)
        val query = floatArrayOf(0f, 0f, 0f, 1f)
        val best = m.best(query, listOf(entrance4, deskMulti, fridge4))
        assertNotNull(best)
        assertEquals("desk", best.label)
        assertEquals(1f, best.score, 1e-6f)
    }

    @Test
    fun `rank returns all landmarks sorted by score desc`() {
        val m = CosineMatcher()
        val query = floatArrayOf(0.9f, 0.3f, 0f)
        val ranked = m.rank(query, all)
        assertEquals(3, ranked.size)
        assertEquals("entrance", ranked.first().label)
        // monotonically non-increasing
        for (i in 1 until ranked.size) {
            assert(ranked[i - 1].score >= ranked[i].score)
        }
    }

    @Test
    fun `empty landmark set yields null`() {
        assertNull(CosineMatcher().best(floatArrayOf(1f, 0f, 0f), emptyList()))
    }
}
