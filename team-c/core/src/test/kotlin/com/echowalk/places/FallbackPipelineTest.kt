package com.echowalk.places

import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the whole Team C pipeline runs end-to-end with the CPU [DownsampleEmbedder] fallback —
 * NO `.pte`, NO NPU, NO Android. Synthetic "camera scenes" are embedded and pushed through the
 * real [FamiliarPlacesNavigator], persisted via [FilePlaceStore], and localized/guided.
 *
 * This is the executable form of plan milestones M-C0..M-C3 on a plain JVM.
 */
class FallbackPipelineTest {

    private val W = 96
    private val H = 96

    private val file: File = File.createTempFile("echowalk-pipeline", ".bin").also { it.delete() }
    private val embedder = DownsampleEmbedder(grid = 16)

    @AfterTest
    fun cleanup() {
        file.delete()
        File(file.absolutePath + ".tmp").delete()
    }

    // ---- synthetic scenes (each a distinct visual layout) -------------------

    private enum class Scene { ENTRANCE, HALLWAY, DESK, STRANGER }

    /** Render a scene to grayscale pixels, with small per-frame jitter to mimic viewpoint noise. */
    private fun frameOf(scene: Scene, rng: Random): FloatArray {
        val px = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val base = when (scene) {
                Scene.ENTRANCE -> x.toFloat() / (W - 1)                 // horizontal gradient
                Scene.HALLWAY -> y.toFloat() / (H - 1)                  // vertical gradient
                Scene.DESK -> if (((x / 8) + (y / 8)) % 2 == 0) 1f else 0f // checkerboard
                Scene.STRANGER -> rng.nextFloat()                      // unseen, structureless
            }
            px[y * W + x] = (base + rng.nextFloat() * 0.02f).coerceIn(0f, 1f)
        }
        return px
    }

    private fun embed(scene: Scene, seed: Int) = embedder.embed(frameOf(scene, Random(seed)), W, H)

    /** M-C0 baseline: enroll three landmarks during a "learning walk", persisting to disk. */
    private fun enrollOffice(nav: FamiliarPlacesNavigator) {
        nav.enrollStart("office", "Office")
        repeat(4) { nav.onEmbedding(embed(Scene.ENTRANCE, it)) }; nav.addLandmark("entrance")
        repeat(4) { nav.onEmbedding(embed(Scene.HALLWAY, 100 + it)) }; nav.addLandmark("hallway")
        repeat(4) { nav.onEmbedding(embed(Scene.DESK, 200 + it)) }; nav.addLandmark("desk")
        nav.enrollStop()
    }

    private fun newNavigator(store: PlaceStore) = FamiliarPlacesNavigator(
        store = store,
        matcher = CosineMatcher(threshold = 0.6f),
        framesPerLandmark = 4,
        confirmTicks = 2,
    )

    // ---- the proof ----------------------------------------------------------

    @Test
    fun `M-C0 same scene scores high, different scenes low`() {
        val a = embed(Scene.DESK, 1)
        val b = embed(Scene.DESK, 2)            // same scene, different jitter
        val c = embed(Scene.ENTRANCE, 3)        // different scene
        assertTrue(Vectors.cosine(a, b) > 0.9f, "same-scene cosine should be high")
        assertTrue(Vectors.cosine(a, c) < 0.6f, "different-scene cosine should be below threshold")
    }

    @Test
    fun `M-C1 enrollment persists across a restart`() {
        enrollOffice(newNavigator(FilePlaceStore(file)))

        // Fresh store from disk == app relaunch.
        val reopened = FilePlaceStore(file)
        val nav = newNavigator(reopened)
        nav.activatePlace("office")
        assertEquals(listOf("entrance", "hallway", "desk"), nav.listDestinations())
    }

    @Test
    fun `M-C2 localizes a revisited spot and stays silent on a stranger spot`() {
        val nav = newNavigator(FilePlaceStore(file))
        enrollOffice(nav)
        nav.activatePlace("office")

        // Revisit the desk -> announced after hysteresis.
        nav.onEmbedding(embed(Scene.DESK, 900))
        val cues = nav.onEmbedding(embed(Scene.DESK, 901))
        assertEquals(1, cues.size)
        assertEquals(CueKind.LOCATED, cues.first().kind)
        assertEquals("desk", cues.first().label)

        // A never-enrolled spot -> no false match.
        val fresh = newNavigator(FilePlaceStore(file)).also { it.activatePlace("office") }
        repeat(5) { assertTrue(fresh.onEmbedding(embed(Scene.STRANGER, 500 + it)).isEmpty()) }
        assertNull(fresh.currentLandmark)
    }

    @Test
    fun `M-C3 guides from entrance to desk via waypoints`() {
        val nav = newNavigator(FilePlaceStore(file))
        val spoken = mutableListOf<PlaceCue>()
        nav.observe { spoken += it }
        enrollOffice(nav)
        nav.activatePlace("office")

        // Localize at the entrance, then ask to be guided to the desk.
        repeat(2) { nav.onEmbedding(embed(Scene.ENTRANCE, 10 + it)) }
        nav.navigateTo("desk")
        assertTrue(nav.isNavigating)

        // Walk the route: hallway, then desk.
        repeat(2) { nav.onEmbedding(embed(Scene.HALLWAY, 300 + it)) }
        repeat(2) { nav.onEmbedding(embed(Scene.DESK, 400 + it)) }

        val kinds = spoken.map { it.kind }
        assertTrue(CueKind.APPROACHING_LANDMARK in kinds, "should approach the hallway waypoint")
        assertEquals(CueKind.ARRIVED, spoken.last().kind)
        assertEquals("desk", spoken.last().label)
        assertTrue(!nav.isNavigating, "navigation ends on arrival")
    }
}
