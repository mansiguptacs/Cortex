package com.echowalk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the camera PreviewView.
 * Draws bounding boxes for all detected YOLO objects.
 *  - Target object (in Find mode): bright green box + label
 *  - Other objects: semi-transparent orange box + label
 *
 * Call [update] from any thread; it marshals to the UI thread automatically.
 */
class ObjectOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Box(
        val label: String,
        val x0: Float, val y0: Float,   // normalized [0, 1]
        val x1: Float, val y1: Float,
        val isTarget: Boolean = false,
    )

    @Volatile private var boxes: List<Box> = emptyList()

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 230, 80)   // bright green
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 230, 80)
        style = Paint.Style.FILL
    }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 140, 0)  // orange
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val otherFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 255, 140, 0)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Update boxes from any thread. Pass an empty list to clear. */
    fun update(newBoxes: List<Box>) {
        boxes = newBoxes
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        for (box in boxes) {
            val rect = RectF(box.x0 * w, box.y0 * h, box.x1 * w, box.y1 * h)
            if (box.isTarget) {
                canvas.drawRect(rect, targetFill)
                canvas.drawRect(rect, targetPaint)
            } else {
                canvas.drawRect(rect, otherFill)
                canvas.drawRect(rect, otherPaint)
            }

            // Label background + text
            val label = if (box.isTarget) "▶ ${box.label}" else box.label
            val textW = labelPaint.measureText(label)
            val textH = labelPaint.textSize
            val labelRect = RectF(rect.left, rect.top - textH - 4f, rect.left + textW + 12f, rect.top)
            labelBgPaint.color = if (box.isTarget) Color.argb(180, 0, 150, 60) else Color.argb(150, 180, 80, 0)
            canvas.drawRect(labelRect, labelBgPaint)
            canvas.drawText(label, rect.left + 6f, rect.top - 6f, labelPaint)
        }
    }
}
