package com.echowalk.places

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddingCodecTest {

    @Test
    fun `round trips an arbitrary vector`() {
        val v = floatArrayOf(0f, 1f, -1f, 3.14159f, -2.5e9f, Float.MIN_VALUE, Float.MAX_VALUE)
        assertContentEquals(v, EmbeddingCodec.decode(EmbeddingCodec.encode(v)))
    }

    @Test
    fun `empty vector encodes to empty bytes and back`() {
        val bytes = EmbeddingCodec.encode(FloatArray(0))
        assertEquals(0, bytes.size)
        assertEquals(0, EmbeddingCodec.decode(bytes).size)
    }

    @Test
    fun `four bytes per float, little-endian`() {
        // 1.0f == 0x3F800000; little-endian byte order is 00 00 80 3F.
        val bytes = EmbeddingCodec.encode(floatArrayOf(1f))
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F.toByte(), bytes[3])
    }

    @Test
    fun `byte length scales with dimension`() {
        assertEquals(512 * 4, EmbeddingCodec.encode(FloatArray(512)).size)
    }

    @Test
    fun `decode rejects a non multiple of four`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            EmbeddingCodec.decode(ByteArray(7))
        }
        assertTrue(ex.message!!.contains("multiple"))
    }
}
