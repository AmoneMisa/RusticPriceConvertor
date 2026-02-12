package com.example.rusticpriceconvertor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class RoiOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFFFF8A00.toInt()
    }

    private val rect = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val minW = dp(160f)
    private val minH = dp(90f)

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val nw = rect.width() * detector.scaleFactor
            val nh = rect.height() * detector.scaleFactor

            val w = max(minW, min(nw, width * 0.95f))
            val h = max(minH, min(nh, height * 0.95f))

            rect.left = cx - w / 2f
            rect.right = cx + w / 2f
            rect.top = cy - h / 2f
            rect.bottom = cy + h / 2f

            clampToBounds()
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (rect.isEmpty) {
            // стартовый ROI по центру
            val rw = w * 0.78f
            val rh = h * 0.16f
            rect.set(
                (w - rw) / 2f,
                (h - rh) / 2f,
                (w + rw) / 2f,
                (h + rh) / 2f
            )
        } else {
            clampToBounds()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(16f)
        canvas.drawRoundRect(rect, r, r, stroke)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = rect.contains(ev.x, ev.y)
                lastX = ev.x
                lastY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaler.isInProgress) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    rect.offset(dx, dy)
                    clampToBounds()
                    invalidate()
                    lastX = ev.x
                    lastY = ev.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    fun getRoiInView(): RectF = RectF(rect)

    private fun clampToBounds() {
        val dx = when {
            rect.left < 0f -> -rect.left
            rect.right > width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.top < 0f -> -rect.top
            rect.bottom > height -> height - rect.bottom
            else -> 0f
        }
        rect.offset(dx, dy)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
