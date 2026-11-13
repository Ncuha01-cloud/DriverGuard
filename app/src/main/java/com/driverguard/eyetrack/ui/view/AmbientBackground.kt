package com.driverguard.eyetrack.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Фон: тёмный радиальный градиент + тонкая сетка скоростных линий,
 * «пульсирующая» в такт уровню усталости.
 */
class AmbientBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0A0F19"), Color.parseColor("#070A11")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        glow.shader = android.graphics.RadialGradient(
            w * 0.85f, h * 0.10f, w * 0.9f,
            intArrayOf(Color.parseColor("#2822D3EE"), Color.parseColor("#00080B12")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        canvas.drawRect(0f, 0f, w, h, glow)

        // сетка перспективных линий
        line.color = Color.parseColor("#0E22D3EE")
        line.strokeWidth = dp(1f)
        val horizon = h * 0.18f
        val vanishX = w * 0.5f
        for (i in -7..7) {
            val xBottom = vanishX + i * w * 0.16f
            canvas.drawLine(vanishX, horizon, xBottom, h, line)
        }
        val path = Path()
        var y = horizon
        var step = h * 0.012f
        while (y < h) {
            val t = (y - horizon) / (h - horizon)
            path.moveTo(0f, y)
            path.lineTo(w, y)
            y += step
            step *= 1.35f
        }
        line.color = Color.parseColor("#0A22D3EE")
        canvas.drawPath(path, line)
    }

    /** Пульс тревоги: мягкая красная виньетка, alpha 0..1 */
    var alarmPulse: Float = 0f
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
