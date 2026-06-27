package com.echowalk.places

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryPlaceStoreTest {

    private val store = InMemoryPlaceStore()

    @AfterTest
    fun tearDown() = store.clear()

    private fun emb(vararg v: Float) = listOf(floatArrayOf(*v))

    @Test
    fun `landmarks get auto incremented ids and per-place order index`() {
        store.createPlace("office", "My Office")
        val a = store.addLandmark("office", "entrance", emb(1f, 0f))
        val b = store.addLandmark("office", "desk", emb(0f, 1f))

        assertTrue(b.id > a.id, "ids must increase")
        assertEquals(0, a.orderIndex)
        assertEquals(1, b.orderIndex)
    }

    @Test
    fun `order index is per place not global`() {
        store.createPlace("office", "Office")
        store.createPlace("store", "Grocery")
        store.addLandmark("office", "entrance", emb(1f, 0f))
        val firstInStore = store.addLandmark("store", "milk", emb(0f, 1f))
        assertEquals(0, firstInStore.orderIndex, "each place starts its own order at 0")
    }

    @Test
    fun `landmarks returned in capture order`() {
        store.createPlace("office", "Office")
        listOf("entrance", "hallway", "desk").forEach { store.addLandmark("office", it, emb(1f, 0f)) }
        assertEquals(listOf("entrance", "hallway", "desk"), store.landmarks("office").map { it.label })
    }

    @Test
    fun `destinations are distinct labels in order`() {
        store.createPlace("office", "Office")
        store.addLandmark("office", "desk", emb(1f, 0f))
        store.addLandmark("office", "kitchen", emb(0f, 1f))
        store.addLandmark("office", "desk", emb(0.9f, 0.1f)) // re-enroll same label
        assertEquals(listOf("desk", "kitchen"), store.destinations("office"))
    }

    @Test
    fun `adding a landmark to unknown place fails`() {
        assertFailsWith<IllegalStateException> {
            store.addLandmark("ghost", "x", emb(1f, 0f))
        }
    }

    @Test
    fun `blank label or empty embeddings rejected`() {
        store.createPlace("office", "Office")
        assertFailsWith<IllegalArgumentException> { store.addLandmark("office", " ", emb(1f, 0f)) }
        assertFailsWith<IllegalArgumentException> { store.addLandmark("office", "ok", emptyList()) }
    }

    @Test
    fun `embeddings are defensively copied`() {
        store.createPlace("office", "Office")
        val raw = floatArrayOf(1f, 0f)
        store.addLandmark("office", "entrance", listOf(raw))
        raw[0] = 99f // mutate caller's buffer after enrollment
        assertEquals(1f, store.landmarks("office").first().embeddings.first()[0], 0f)
    }

    @Test
    fun `deletePlace removes its landmarks`() {
        store.createPlace("office", "Office")
        store.addLandmark("office", "desk", emb(1f, 0f))
        store.deletePlace("office")
        assertNull(store.getPlace("office"))
        assertTrue(store.landmarks("office").isEmpty())
    }

    @Test
    fun `clear resets id counter`() {
        store.createPlace("office", "Office")
        store.addLandmark("office", "desk", emb(1f, 0f))
        store.clear()
        store.createPlace("office", "Office")
        val first = store.addLandmark("office", "desk", emb(1f, 0f))
        assertEquals(1L, first.id, "id counter resets after clear")
    }

    @Test
    fun `integrates with matcher over stored landmarks`() {
        store.createPlace("office", "Office")
        store.addLandmark("office", "entrance", emb(1f, 0f, 0f))
        store.addLandmark("office", "desk", emb(0f, 1f, 0f))
        val match = CosineMatcher(threshold = 0.8f)
            .best(floatArrayOf(0.05f, 0.99f, 0f), store.landmarks("office"))
        assertEquals("desk", match?.label)
    }
}
