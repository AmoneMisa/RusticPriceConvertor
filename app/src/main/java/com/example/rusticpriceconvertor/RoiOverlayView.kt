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
    private val minW = dp(160f)
    private val minH = dp(90f)

    private val scaler =
        ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

    private val touchSlop = dp(24f)

    private enum class Mode { NONE, DRAG, RESIZE_L, RESIZE_R, RESIZE_T, RESIZE_B, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    private var mode = Mode.NONE

    private fun pickMode(x: Float, y: Float): Mode {
        val nearL = kotlin.math.abs(x - rect.left) <= touchSlop
        val nearR = kotlin.math.abs(x - rect.right) <= touchSlop
        val nearT = kotlin.math.abs(y - rect.top) <= touchSlop
        val nearB = kotlin.math.abs(y - rect.bottom) <= touchSlop
        val inside = rect.contains(x, y)

        return when {
            nearL && nearT -> Mode.RESIZE_TL
            nearR && nearT -> Mode.RESIZE_TR
            nearL && nearB -> Mode.RESIZE_BL
            nearR && nearB -> Mode.RESIZE_BR
            nearL -> Mode.RESIZE_L
            nearR -> Mode.RESIZE_R
            nearT -> Mode.RESIZE_T
            nearB -> Mode.RESIZE_B
            inside -> Mode.DRAG
            else -> Mode.NONE
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = pickMode(ev.x, ev.y)
                lastX = ev.x
                lastY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaler.isInProgress) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY

                    when (mode) {
                        Mode.DRAG -> rect.offset(dx, dy)

                        Mode.RESIZE_L, Mode.RESIZE_TL, Mode.RESIZE_BL -> rect.left += dx
                        Mode.RESIZE_R, Mode.RESIZE_TR, Mode.RESIZE_BR -> rect.right += dx
                        else -> {}
                    }

                    when (mode) {
                        Mode.RESIZE_T, Mode.RESIZE_TL, Mode.RESIZE_TR -> rect.top += dy
                        Mode.RESIZE_B, Mode.RESIZE_BL, Mode.RESIZE_BR -> rect.bottom += dy
                        else -> {}
                    }

                    if (rect.width() < minW) {
                        val cx = rect.centerX()
                        rect.left = cx - minW / 2f
                        rect.right = cx + minW / 2f
                    }
                    if (rect.height() < minH) {
                        val cy = rect.centerY()
                        rect.top = cy - minH / 2f
                        rect.bottom = cy + minH / 2f
                    }

                    clampToBounds()
                    invalidate()

                    lastX = ev.x
                    lastY = ev.y
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return true
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
