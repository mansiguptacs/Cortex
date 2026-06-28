package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateGateTest {

    @Test
    fun `first call always passes`() {
        assertTrue(RateGate(500).allow(0))
    }

    @Test
    fun `blocks within the interval and passes once it elapses`() {
        val gate = RateGate(500)
        assertTrue(gate.allow(1_000))
        assertFalse(gate.allow(1_200), "200ms < 500ms")
        assertFalse(gate.allow(1_499), "just under the interval")
        assertTrue(gate.allow(1_500), "exactly the interval elapsed")
        assertFalse(gate.allow(1_700))
        assertTrue(gate.allow(2_000))
    }

    @Test
    fun `roughly caps a busy stream to the target rate`() {
        val gate = RateGate.hz(2.0) // one pass every 500ms
        // Simulate a 30 fps stream for one second (frames every ~33ms).
        val passes = (0 until 30).count { gate.allow(it * 33L) }
        // 1000ms / 500ms == ~2 passes (first frame + one more around the 500ms mark).
        assertTrue(passes in 2..3, "expected ~2 passes for 1s at 2Hz, got $passes")
    }

    @Test
    fun `hz factory computes the interval`() {
        // 2 Hz -> 500ms interval.
        val gate = RateGate.hz(2.0)
        assertTrue(gate.allow(0))
        assertFalse(gate.allow(499))
        assertTrue(gate.allow(500))
    }

    @Test
    fun `reset clears the last pass time`() {
        val gate = RateGate(500)
        assertTrue(gate.allow(1_000))
        assertFalse(gate.allow(1_100))
        gate.reset()
        assertTrue(gate.allow(1_100), "after reset the next call passes immediately")
    }

    @Test
    fun `zero interval passes every time`() {
        val gate = RateGate(0)
        assertTrue(gate.allow(0))
        assertTrue(gate.allow(0))
        assertTrue(gate.allow(0))
    }

    @Test
    fun `rejects negative interval and non positive rate`() {
        assertFailsWith<IllegalArgumentException> { RateGate(-1) }
        assertFailsWith<IllegalArgumentException> { RateGate.hz(0.0) }
    }
}
