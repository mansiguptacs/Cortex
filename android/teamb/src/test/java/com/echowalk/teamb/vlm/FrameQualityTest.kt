package com.echowalk.teamb.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {

    @Test fun `black frame is too dark`() {
        val black = IntArray(16) { 0xFF000000.toInt() }
        assertTrue(FrameQuality.isTooDark(black))
        assertEquals(0f, FrameQuality.meanLuma(black), 1e-3f)
    }

    @Test fun `white frame is bright`() {
        val white = IntArray(16) { 0xFFFFFFFF.toInt() }
        assertFalse(FrameQuality.isTooDark(white))
        assertEquals(255f, FrameQuality.meanLuma(white), 1f)
    }

    @Test fun `mid gray is not too dark`() {
        val gray = IntArray(16) { 0xFF808080.toInt() }
        assertFalse(FrameQuality.isTooDark(gray))
    }

    @Test fun `empty is treated as dark`() {
        assertTrue(FrameQuality.isTooDark(IntArray(0)))
    }

    @Test fun `flat image is blurry, edges are sharp`() {
        val w = 8
        val h = 8
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        assertEquals(0.0, FrameQuality.sharpness(flat, w, h), 1e-6)
        assertTrue(FrameQuality.isBlurry(flat, w, h))

        // Checkerboard: maximal local contrast -> high focus measure.
        val checker = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        assertTrue(FrameQuality.sharpness(checker, w, h) > 100.0)
        assertFalse(FrameQuality.isBlurry(checker, w, h))
    }
}
