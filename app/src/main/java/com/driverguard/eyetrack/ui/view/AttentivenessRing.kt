package com.driverguard.eyetrack.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Кольцо «Уровень бдительности» — главный индикатор экрана наблюдения.
 */
class AttentivenessRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ok = Color.parseColor("#22E3A0")
    private val warn = Color.parseColor("#FFB020")
    private val danger = Color.parseColor("#FF4D6D")
    private val track = Color.parseColor("#162034")

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33FFFFFF")
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AABC9")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }

    private var progress = 1f
    private var displayed = 1f
    private var animator: ValueAnimator? = null

    var level: Int = 0
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    fun setProgress(value: Float, animate: Boolean = true) {
        progress = value.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            displayed = progress
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayed, progress).apply {
            duration = 450
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                displayed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun colorForLevel(): Int = when (level) {
        3 -> danger
        2 -> warn
        1 -> warn
        else -> ok
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f - dp(14f)
        val cx = w / 2f
        val cy = h / 2f
        val strokeW = dp(13f)
        ring.strokeWidth = strokeW

        // дорожка
        ring.color = track
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f + 8f, 344f, false, ring)

        // сегменты-деления
        tickPaint.strokeWidth = dp(1.5f)
        val rOut = r + strokeW / 2f + dp(9f)
        for (i in 0..59) {
            val angle = Math.toRadians((-90 + i * 6 - 8).toDouble())
            val longTick = i % 5 == 0
            val len = if (longTick) dp(8f) else dp(4f)
            val x1 = cx + (rOut * Math.cos(angle)).toFloat()
            val y1 = cy + (rOut * Math.sin(angle)).toFloat()
            val x2 = cx + ((rOut + len) * Math.cos(angle)).toFloat()
            val y2 = cy + ((rOut + len) * Math.sin(angle)).toFloat()
            tickPaint.alpha = if (longTick) 110 else 55
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // прогресс
        val sweep = 344f * displayed
        val c = colorForLevel()
        ring.shader = SweepGradient(
            cx, cy,
            intArrayOf(c, adjust(c, 0.6f)),
            floatArrayOf(0f, 1f)
        )
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f + 8f, sweep, false, ring)
        ring.shader = null

        // кончик
        if (sweep > 0f) {
            val end = Math.toRadians((-90 + 8 + sweep).toDouble())
            val ex = cx + (r * Math.cos(end)).toFloat()
            val ey = cy + (r * Math.sin(end)).toFloat()
            fill.color = c
            fill.alpha = 90
            canvas.drawCircle(ex, ey, dp(11f), fill)
            fill.alpha = 255
            canvas.drawCircle(ex, ey, dp(6f), fill)
        }

        // центр
        text.color = Color.parseColor("#F2F6FF")
        text.textSize = dp(46f)
        val pct = "${(displayed * 100).toInt()}"
        canvas.drawText(pct, cx, cy + dp(14f), text)
        label.textSize = dp(10f)
        label.color = colorForLevel()
        canvas.drawText("БДИТЕЛЬНОСТЬ", cx, cy + dp(36f), label)
    }

    private fun adjust(color: Int, k: Float): Int {
        val r = (Color.red(color) * k).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * k).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * k).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
