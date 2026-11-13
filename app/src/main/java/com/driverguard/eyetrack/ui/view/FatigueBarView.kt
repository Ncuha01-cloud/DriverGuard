package com.driverguard.eyetrack.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Горизонтальный индикатор накопленной усталости с градиентом зелёный → жёлтый → красный. */
class FatigueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2233")
        style = Paint.Style.FILL
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = dp(2f)
    }

    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var shown = 0f
    private val animator = android.animation.ValueAnimator()

    init {
        animator.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        animator.duration = 300
    }

    fun animateTo(value: Float) {
        animator.cancel()
        animator.setFloatValues(shown, value.coerceIn(0f, 1f))
        animator.start()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, track)
        if (shown > 0.001f) {
            val rect = RectF(0f, 0f, w * shown, h)
            bar.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#22E3A0"), Color.parseColor("#FFB020"), Color.parseColor("#FF4D6D")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, bar)
            // маркер
            val x = (w * shown).coerceIn(r, w - r)
            canvas.drawCircle(x, h / 2f, r * 0.45f, marker)
        }
    }
}
