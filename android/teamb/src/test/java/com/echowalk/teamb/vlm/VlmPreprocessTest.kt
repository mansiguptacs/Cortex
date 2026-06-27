package com.echowalk.teamb.vlm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fast JVM tests for the pure preprocessing core (no Android / no device needed).
 * Run: `./gradlew :teamb:testDebugUnitTest`
 */
class VlmPreprocessTest {

    @Test
    fun `chw layout is planar with normalized channels`() {
        // 2x1 image: pixel 0 = white (0xFFFFFFFF), pixel 1 = black (0xFF000000).
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())

        val out = VlmPreprocess.pixelsToCHW(pixels, w = 2, h = 1)

        assertEquals(6, out.size) // 3 channels * 2 pixels
        // (1.0 - 0.5)/0.5 = 1.0 for white, (0 - 0.5)/0.5 = -1.0 for black.
        // R plane
        assertEquals(1.0f, out[0], 1e-6f)
        assertEquals(-1.0f, out[1], 1e-6f)
        // G plane
        assertEquals(1.0f, out[2], 1e-6f)
        assertEquals(-1.0f, out[3], 1e-6f)
        // B plane
        assertEquals(1.0f, out[4], 1e-6f)
        assertEquals(-1.0f, out[5], 1e-6f)
    }

    @Test
    fun `mid gray maps near zero`() {
        val gray = 0xFF808080.toInt()
        val out = VlmPreprocess.pixelsToCHW(intArrayOf(gray), w = 1, h = 1)
        // 128/255 = 0.50196..., normalized ~= 0.00392
        assertEquals(0.00392f, out[0], 1e-4f)
        assertEquals(0.00392f, out[1], 1e-4f)
        assertEquals(0.00392f, out[2], 1e-4f)
    }
}
