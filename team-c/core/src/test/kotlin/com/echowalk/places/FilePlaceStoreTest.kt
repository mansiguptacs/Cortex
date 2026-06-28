package com.echowalk.places

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilePlaceStoreTest {

    // A path that does not exist yet; FilePlaceStore creates it on first write.
    private val file: File = File.createTempFile("echowalk-places", ".bin").also { it.delete() }

    @AfterTest
    fun cleanup() {
        file.delete()
        File(file.absolutePath + ".tmp").delete()
    }

    /** M-C1: enroll 3 landmarks, "relaunch" (new instance, same file), they're still there. */
    @Test
    fun `landmarks survive a restart`() {
        run {
            val store = FilePlaceStore(file)
            store.createPlace("office", "Office")
            store.addLandmark("office", "entrance", listOf(floatArrayOf(1f, 0f, 0f)), headingDeg = 10f)
            store.addLandmark(
                "office",
                "hallway",
                listOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0.9f, 0.1f)),
                headingDeg = null,
            )
            store.addLandmark("office", "desk", listOf(floatArrayOf(0f, 0f, 1f)), headingDeg = 200f)
        }

        // Fresh instance == simulated app relaunch from disk.
        val reopened = FilePlaceStore(file)

        assertNotNull(reopened.getPlace("office"))
        assertEquals("Office", reopened.getPlace("office")!!.name)
        assertEquals(listOf("entrance", "hallway", "desk"), reopened.destinations("office"))

        val lms = reopened.landmarks("office")
        assertEquals(3, lms.size)
        assertEquals(listOf(0, 1, 2), lms.map { it.orderIndex }, "capture order preserved")

        // Multi-frame enrollment survives exactly.
        assertEquals(2, lms[1].embeddings.size)
        assertContentEquals(floatArrayOf(0f, 0.9f, 0.1f), lms[1].embeddings[1])

        // Headings (including the null one) round-trip.
        assertEquals(10f, lms[0].headingDeg)
        assertNull(lms[1].headingDeg)
        assertEquals(200f, lms[2].headingDeg)
    }

    @Test
    fun `empty place with no landmarks survives a restart`() {
        FilePlaceStore(file).createPlace("garage", "Garage")
        val reopened = FilePlaceStore(file)
        assertNotNull(reopened.getPlace("garage"))
        assertEquals(emptyList(), reopened.landmarks("garage"))
    }

    @Test
    fun `reopened store integrates with the matcher`() {
        FilePlaceStore(file).apply {
            createPlace("p", "P")
            addLandmark("p", "a", listOf(floatArrayOf(1f, 0f, 0f)))
            addLandmark("p", "b", listOf(floatArrayOf(0f, 1f, 0f)))
        }

        val store = FilePlaceStore(file)
        val match = CosineMatcher(threshold = 0.8f)
            .best(floatArrayOf(0.95f, 0.05f, 0f), store.landmarks("p"))

        assertNotNull(match)
        assertEquals("a", match.label)
    }

    @Test
    fun `deletePlace is persisted`() {
        FilePlaceStore(file).apply {
            createPlace("p", "P")
            addLandmark("p", "a", listOf(floatArrayOf(1f)))
            deletePlace("p")
        }
        assertNull(FilePlaceStore(file).getPlace("p"))
    }

    @Test
    fun `clear is persisted`() {
        FilePlaceStore(file).apply {
            createPlace("p", "P")
            addLandmark("p", "a", listOf(floatArrayOf(1f)))
            clear()
        }
        assertEquals(emptyList(), FilePlaceStore(file).places())
    }

    @Test
    fun `each reload decodes independent embedding buffers`() {
        FilePlaceStore(file).apply {
            createPlace("p", "P")
            addLandmark("p", "a", listOf(floatArrayOf(1f, 2f, 3f)))
        }
        // Two separate "relaunches" from the same file must not share backing arrays.
        val storeA = FilePlaceStore(file)
        val storeB = FilePlaceStore(file)
        storeA.landmarks("p").first().embeddings.first()[0] = 99f
        assertContentEquals(
            floatArrayOf(1f, 2f, 3f),
            storeB.landmarks("p").first().embeddings.first(),
            "a separate reload must be unaffected by mutations to another instance",
        )
    }
}
