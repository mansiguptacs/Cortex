package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FamiliarPlacesNavigatorTest {

    // Distinct one-hot-ish embeddings per spot so cosine cleanly separates them.
    private val vEntrance = floatArrayOf(1f, 0f, 0f)
    private val vHallway = floatArrayOf(0f, 1f, 0f)
    private val vDesk = floatArrayOf(0f, 0f, 1f)
    private val vUnknown = floatArrayOf(0.58f, 0.58f, 0.58f)

    private fun newNavigator(confirmTicks: Int = 2) = FamiliarPlacesNavigator(
        store = InMemoryPlaceStore(),
        matcher = CosineMatcher(threshold = 0.8f),
        framesPerLandmark = 3,
        confirmTicks = confirmTicks,
    )

    /** Enroll entrance(N) -> hallway(N) -> desk(E) by streaming frames + labeling. */
    private fun FamiliarPlacesNavigator.enrollOffice() {
        enrollStart("office", "Office")
        repeat(3) { onEmbedding(vEntrance) }; addLandmarkWithHeading("entrance")
        repeat(3) { onEmbedding(vHallway) }; addLandmarkWithHeading("hallway")
        repeat(3) { onEmbedding(vDesk) }; addLandmarkWithHeading("desk")
        enrollStop()
    }

    // headings are stored via the store directly in enroll; here we just use addLandmark,
    // headings default null (turn cues are covered separately in RouteEngineTest).
    private fun FamiliarPlacesNavigator.addLandmarkWithHeading(label: String) = addLandmark(label)

    @Test
    fun `enroll persists landmarks in capture order`() {
        val nav = newNavigator()
        nav.enrollOffice()
        nav.activatePlace("office")
        assertEquals(listOf("entrance", "hallway", "desk"), nav.listDestinations())
    }

    @Test
    fun `addLandmark before enrollStart fails`() {
        val nav = newNavigator()
        try {
            nav.addLandmark("x"); assertTrue(false, "should have thrown")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test
    fun `localization announces only after hysteresis confirms`() {
        val nav = newNavigator(confirmTicks = 3)
        nav.enrollOffice()
        nav.activatePlace("office")

        assertTrue(nav.onEmbedding(vDesk).isEmpty(), "tick 1: not yet confirmed")
        assertTrue(nav.onEmbedding(vDesk).isEmpty(), "tick 2: not yet confirmed")
        val cues = nav.onEmbedding(vDesk)
        assertEquals(1, cues.size)
        assertEquals(CueKind.LOCATED, cues.first().kind)
        assertEquals("desk", cues.first().label)
    }

    @Test
    fun `unknown spot stays silent`() {
        val nav = newNavigator(confirmTicks = 2)
        nav.enrollOffice()
        nav.activatePlace("office")
        repeat(5) { assertTrue(nav.onEmbedding(vUnknown).isEmpty()) }
        assertNull(nav.currentLandmark)
    }

    @Test
    fun `does not re-announce the same landmark every tick`() {
        val nav = newNavigator(confirmTicks = 2)
        nav.enrollOffice()
        nav.activatePlace("office")
        nav.onEmbedding(vDesk)
        val first = nav.onEmbedding(vDesk) // confirmed here
        assertEquals(1, first.size)
        assertTrue(nav.onEmbedding(vDesk).isEmpty(), "still at desk -> no repeat announcement")
        assertTrue(nav.onEmbedding(vDesk).isEmpty())
    }

    @Test
    fun `guidance walks waypoints to arrival`() {
        val nav = newNavigator(confirmTicks = 2)
        val emitted = mutableListOf<PlaceCue>()
        nav.observe { emitted += it }
        nav.enrollOffice()
        nav.activatePlace("office")

        // Start localized at the entrance.
        repeat(2) { nav.onEmbedding(vEntrance) }
        nav.navigateTo("desk")
        assertTrue(nav.isNavigating)

        // Walk: hallway, then desk.
        repeat(2) { nav.onEmbedding(vHallway) }
        repeat(2) { nav.onEmbedding(vDesk) }

        val kinds = emitted.map { it.kind }
        assertTrue(CueKind.APPROACHING_LANDMARK in kinds, "should approach hallway")
        assertEquals(CueKind.ARRIVED, emitted.last().kind)
        assertEquals("desk", emitted.last().label)
        assertFalse(nav.isNavigating, "navigation ends on arrival")
    }

    @Test
    fun `navigate reverse direction works`() {
        val nav = newNavigator(confirmTicks = 2)
        nav.enrollOffice()
        nav.activatePlace("office")
        repeat(2) { nav.onEmbedding(vDesk) } // start at desk
        nav.navigateTo("entrance")
        val out = mutableListOf<PlaceCue>()
        nav.observe { out += it }
        repeat(2) { nav.onEmbedding(vHallway) }
        repeat(2) { nav.onEmbedding(vEntrance) }
        assertEquals(CueKind.ARRIVED, out.last().kind)
        assertEquals("entrance", out.last().label)
    }
}
