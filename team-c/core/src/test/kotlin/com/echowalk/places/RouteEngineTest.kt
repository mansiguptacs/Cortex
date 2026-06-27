package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteEngineTest {

    private fun lm(id: Long, label: String, order: Int, heading: Float?) =
        Landmark(id, "office", label, listOf(floatArrayOf(1f, 0f)), heading, order)

    // entrance(N) -> hallway(N) -> desk(E): a right turn happens approaching the desk.
    private val route = listOf(
        lm(1, "entrance", 0, 0f),
        lm(2, "hallway", 1, 0f),
        lm(3, "desk", 2, 90f),
    )

    private val engine = RouteEngine(turnThresholdDeg = 30f)

    @Test
    fun `plans forward contiguous route`() {
        val r = engine.planRoute(route, "entrance", "desk")
        assertEquals(listOf("entrance", "hallway", "desk"), r.map { it.label })
    }

    @Test
    fun `plans reverse route when destination enrolled earlier`() {
        val r = engine.planRoute(route, "desk", "entrance")
        assertEquals(listOf("desk", "hallway", "entrance"), r.map { it.label })
    }

    @Test
    fun `missing endpoint yields empty route`() {
        assertTrue(engine.planRoute(route, "entrance", "rooftop").isEmpty())
        assertTrue(engine.planRoute(route, "ghost", "desk").isEmpty())
    }

    @Test
    fun `already at destination yields single arrived cue`() {
        val cues = engine.guide(route, "desk", "desk")
        assertEquals(1, cues.size)
        assertEquals(CueKind.ARRIVED, cues.first().kind)
        assertEquals("desk", cues.first().label)
    }

    @Test
    fun `cue sequence ends with arrived at destination`() {
        val cues = engine.guide(route, "entrance", "desk")
        assertEquals(CueKind.ARRIVED, cues.last().kind)
        assertEquals("desk", cues.last().label)
        // exactly one ARRIVED, and it's the last
        assertEquals(1, cues.count { it.kind == CueKind.ARRIVED })
    }

    @Test
    fun `emits a turn cue where heading changes beyond threshold`() {
        val cues = engine.guide(route, "entrance", "desk")
        val turn = cues.firstOrNull { it.kind == CueKind.TURN }
        assertTrue(turn != null, "expected a TURN cue approaching the desk")
        assertEquals("desk", turn.label)
        assertEquals(90f, turn.directionDeg!!, 1e-4f) // right turn
        assertEquals("right", turn.distanceHint)
    }

    @Test
    fun `no turn cue when path is straight`() {
        val straight = listOf(
            lm(1, "a", 0, 0f),
            lm(2, "b", 1, 5f),
            lm(3, "c", 2, 350f), // within +-30 of straight relative to b
        )
        val cues = engine.guide(straight, "a", "c")
        assertEquals(0, cues.count { it.kind == CueKind.TURN })
        assertEquals(listOf(CueKind.APPROACHING_LANDMARK, CueKind.ARRIVED), cues.map { it.kind })
    }

    @Test
    fun `handles missing headings without turn cues`() {
        val noHeading = listOf(
            lm(1, "a", 0, null),
            lm(2, "b", 1, null),
        )
        val cues = engine.guide(noHeading, "a", "b")
        assertEquals(listOf(CueKind.ARRIVED), cues.map { it.kind })
    }
}
