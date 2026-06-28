package com.echowalk.places

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs a CLIP embedding ([FloatArray]) to/from a compact [ByteArray].
 *
 * This is the exact body the Android Room `@TypeConverter` will reuse to store embeddings in the
 * `Landmark.embedding` column (the plan's schema keeps embeddings as packed `float[]`), and it is
 * also what [FilePlaceStore] uses to persist vectors. Kept dependency-free and pure so it is
 * unit-testable on the JVM (Lane 1) with no Android.
 *
 * Layout: little-endian IEEE-754 floats, 4 bytes each, no header. The element count is implied by
 * the byte length, so the caller is responsible for knowing/recording the embedding dimension if
 * it ever needs validation.
 */
object EmbeddingCodec {

    /** Pack [v] into `v.size * 4` little-endian bytes. */
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    /** Unpack a little-endian float buffer. [bytes] length must be a multiple of 4. */
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) {
            "embedding byte length ${bytes.size} is not a multiple of ${Float.SIZE_BYTES}"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / Float.SIZE_BYTES)
        for (i in out.indices) out[i] = buf.float
        return out
    }
}
