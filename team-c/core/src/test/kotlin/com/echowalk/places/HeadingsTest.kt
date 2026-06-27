package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadingsTest {

    @Test
    fun `normalize wraps into 0 to 360`() {
        assertEquals(10f, Headings.normalize360(370f), 1e-4f)
        assertEquals(350f, Headings.normalize360(-10f), 1e-4f)
        assertEquals(0f, Headings.normalize360(360f), 1e-4f)
    }

    @Test
    fun `relative turn is signed and shortest`() {
        assertEquals(90f, Headings.relativeTurn(0f, 90f)!!, 1e-4f)   // right
        assertEquals(-90f, Headings.relativeTurn(90f, 0f)!!, 1e-4f)  // left
    }

    @Test
    fun `relative turn wraps across north`() {
        // 350 -> 10 is a +20 right turn, not -340.
        assertEquals(20f, Headings.relativeTurn(350f, 10f)!!, 1e-4f)
        // 10 -> 350 is a -20 left turn.
        assertEquals(-20f, Headings.relativeTurn(10f, 350f)!!, 1e-4f)
    }

    @Test
    fun `relative turn null when heading unknown`() {
        assertNull(Headings.relativeTurn(null, 10f))
        assertNull(Headings.relativeTurn(10f, null))
    }

    @Test
    fun `describe turn buckets left straight right`() {
        assertEquals("right", Headings.describeTurn(90f))
        assertEquals("left", Headings.describeTurn(-90f))
        assertEquals("straight", Headings.describeTurn(10f))
    }
}
