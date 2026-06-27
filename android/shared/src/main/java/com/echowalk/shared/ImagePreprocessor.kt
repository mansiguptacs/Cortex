package com.echowalk.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Image-preprocessing helpers shared by every team. Both Depth-Anything-V2 (518×518 NHWC) and
 * YOLOv10-Det (640×640 NHWC) take fp32 RGB inputs scaled to [0,1]; this builds the right
 * direct ByteBuffer in one pass.
 */
object ImagePreprocessor {

    /** Reusable scaled-bitmap cache keyed by target size, to avoid per-frame allocation. */
    private val scaledCache = HashMap<Long, Bitmap>(4)

    /**
     * Letterbox-resize [src] to [targetW] × [targetH], fill RGB into a direct fp32 buffer with
     * values in [0, 1], NHWC layout matching AI Hub TFLite exports.
     */
    fun toFp32Nhwc(src: Bitmap, targetW: Int, targetH: Int, out: ByteBuffer? = null): ByteBuffer {
        val resized = letterbox(src, targetW, targetH)
        val bytes = targetW * targetH * 3 * 4
        val buf = (out ?: ByteBuffer.allocateDirect(bytes)).order(ByteOrder.nativeOrder())
        buf.rewind()
        val px = IntArray(targetW * targetH)
        resized.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        val fb = buf.asFloatBuffer()
        for (i in px.indices) {
            val p = px[i]
            fb.put(((p shr 16) and 0xFF) / 255f)
            fb.put(((p shr 8) and 0xFF) / 255f)
            fb.put((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun letterbox(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val key = (w.toLong() shl 32) or h.toLong()
        val canvasBmp = scaledCache.getOrPut(key) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(canvasBmp)
        canvas.drawARGB(255, 114, 114, 114)
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = src.width * scale
        val sh = src.height * scale
        val left = (w - sw) / 2f
        val top = (h - sh) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return canvasBmp
    }
}
