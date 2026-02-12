package com.example.rusticpriceconvertor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

class TickerCircleDrawable(
    private val text: String,
    private val bgColor: Int,
    private val textColor: Int,
    density: Float,
    private val strokeColor: Int? = null
) : Drawable() {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = strokeColor ?: bgColor
        alpha = 160
    }

    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun fitTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxSize: Float
    ): Float {
        var size = maxSize
        paint.textSize = size
        while (paint.measureText(text) > maxWidth && size > 6f) {
            size -= 1f
            paint.textSize = size
        }
        return size
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = min(b.width(), b.height()) / 2f

        canvas.drawCircle(cx, cy, r, bg)
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)

        val label = text.take(5)
        val maxTextWidth = r * 1.5f
        fg.textSize = fitTextSize(fg, label, maxTextWidth, r * 0.9f)

        val fm = fg.fontMetrics
        val y = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text.take(5), cx, y, fg)
    }

    override fun setAlpha(alpha: Int) {
        bg.alpha = alpha
        fg.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        bg.colorFilter = cf
        fg.colorFilter = cf
        stroke.colorFilter = cf
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
