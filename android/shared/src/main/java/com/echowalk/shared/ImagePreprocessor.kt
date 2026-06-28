package com.echowalk.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Image-preprocessing helpers shared by every team. Both Depth-Anything-V2 (518×518 NHWC) and
 * YOLOv10-Det (640×640 NHWC) take fp32 RGB inputs scaled to [0,1]; this builds the right
 * direct ByteBuffer in one pass.
 *
 * Thread-safety: all caches are [ConcurrentHashMap] so concurrent callers with different target
 * sizes (e.g. a depth thread at 518px and a YOLO thread at 640px) never race on the map.
 * Each size key maps to a dedicated Bitmap/IntArray/Matrix that is only ever used by one caller
 * at a time (the frame pipeline guarantees no two preprocess jobs share a size simultaneously).
 */
object ImagePreprocessor {

    /** Reusable scaled-bitmap cache keyed by target size. Thread-safe for concurrent size keys. */
    private val scaledCache = ConcurrentHashMap<Long, Bitmap>(4)

    /** Reusable pixel-extraction buffer keyed by target size; avoids per-frame IntArray allocation. */
    private val pixelCache = ConcurrentHashMap<Long, IntArray>(4)

    /** Reusable Matrix per size key; Matrix is not internally thread-safe but each key is used by
     *  only one thread at a time within our pipeline. */
    private val matrixCache = ConcurrentHashMap<Long, Matrix>(4)

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Letterbox-resize [src] to [targetW] × [targetH], fill RGB into a direct fp32 buffer with
     * values in [0, 1], NHWC layout matching AI Hub TFLite exports.
     *
     * Pass a pre-allocated [out] buffer to avoid per-frame `allocateDirect` — the caller is
     * responsible for ensuring [out] has capacity ≥ targetW * targetH * 3 * 4.
     */
    fun toFp32Nhwc(src: Bitmap, targetW: Int, targetH: Int, out: ByteBuffer? = null): ByteBuffer {
        val resized = letterbox(src, targetW, targetH)
        val bytes = targetW * targetH * 3 * 4
        val buf = (out ?: ByteBuffer.allocateDirect(bytes)).order(ByteOrder.nativeOrder())
        buf.rewind()

        val key = sizeKey(targetW, targetH)
        val px = pixelCache.getOrPut(key) { IntArray(targetW * targetH) }
        resized.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        val fb = buf.asFloatBuffer()
        for (p in px) {
            fb.put(((p shr 16) and 0xFF) / 255f)
            fb.put(((p shr 8) and 0xFF) / 255f)
            fb.put((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun letterbox(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val key = sizeKey(w, h)
        val canvasBmp = scaledCache.getOrPut(key) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val matrix = matrixCache.getOrPut(key) { Matrix() }
        val canvas = Canvas(canvasBmp)
        canvas.drawARGB(255, 114, 114, 114)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = src.width * scale
        val sh = src.height * scale
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - sw) / 2f, (h - sh) / 2f)
        canvas.drawBitmap(src, matrix, filterPaint)
        return canvasBmp
    }

    private fun sizeKey(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()
}
