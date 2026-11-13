package com.driverguard.eyetrack.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.driverguard.eyetrack.core.FaceSnapshot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HUD наблюдения — рисуется поверх CameraView:
 *  - рамка лица с «уголками» и сканирующей линией;
 *  - якоря глаз и полоски открытости (EAR);
 *  - дистанционное кольцо внимания с делением; шкала наклона головы;
 *  - индикатор «микро-сона» (доля закрытия глаз).
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val accent = Color.parseColor("#22D3EE")
    private val ok = Color.parseColor("#22E3A0")
    private val warn = Color.parseColor("#FFB020")
    private val danger = Color.parseColor("#FF4D6D")
    private val ink = Color.parseColor("#F2F6FF")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(dp(10f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }

    /** 0 — нет лица, 1 — OK, 2 — предупреждение, 3 — тревога */
    var level: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var snapshot: FaceSnapshot? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Поступает из MainActivity: фронтальная камера зеркалит превью. */
    var mirrored: Boolean = true

    private var scanAnim: ValueAnimator? = null
    private var scanY = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scanAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                scanY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        scanAnim?.cancel()
        scanAnim = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = snapshot ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val stateColor = when (level) {
            3 -> danger
            2 -> warn
            1 -> warn
            else -> if (s.faceFound) ok else warn
        }

        // Лицо занимает центральную «арку» превью. Центр ROI ~ (0.5, 0.40)
        val cx = w * 0.5f
        val cy = h * 0.40f
        val fw = w * 0.52f
        val fh = h * 0.36f

        // Если есть реальный bounding box от ML Kit — рамка идёт по нему
        val b = s.box
        val fx = if (b != null) {
            val l = (if (mirrored) 1f - b[2] else b[0]) * w
            val t = b[1] * h
            val r = (if (mirrored) 1f - b[0] else b[2]) * w
            val bo = b[3] * h
            val pad = dp(6f)
            floatArrayOf(l - pad, t - pad, r + pad, bo + pad)
        } else {
            floatArrayOf(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2)
        }

        drawFaceFrame(canvas, fx, stateColor, s)

        if (s.faceFound) {
            drawEyes(canvas, cx, cy, fw, fh, stateColor, s)
            drawHeadGauge(canvas, cx, h * 0.78f, w * 0.34f, s)
            drawStatsPills(canvas, cx, h * 0.90f, s, stateColor)
        } else {
            drawSearching(canvas, cx, cy, w)
        }
    }

    private fun drawFaceFrame(canvas: Canvas, box: FloatArray, color: Int, s: FaceSnapshot) {
        val left = box[0]
        val top = box[1]
        val right = box[2]
        val bottom = box[3]
        val r = dp(26f)
        val c = dp(24f)

        // Мягкое свечение рамки
        fill.color = color
        fill.alpha = if (s.faceFound) 18 else 8
        val rr = android.graphics.RectF(left, top, right, bottom)
        canvas.drawRoundRect(rr, r, r, fill)

        stroke.color = color
        stroke.strokeWidth = dp(2.2f)
        stroke.alpha = if (s.faceFound) 230 else 120
        // Уголки
        val path = Path()
        // TL
        path.moveTo(left, top + c); path.lineTo(left, top + r); path.quadTo(left, top, left + r, top); path.lineTo(left + c, top)
        // TR
        path.moveTo(right - c, top); path.lineTo(right - r, top); path.quadTo(right, top, right, top + r); path.lineTo(right, top + c)
        // BR
        path.moveTo(right, bottom - c); path.lineTo(right, bottom - r); path.quadTo(right, bottom, right - r, bottom); path.lineTo(right - c, bottom)
        // BL
        path.moveTo(left + c, bottom); path.lineTo(left + r, bottom); path.quadTo(left, bottom, left, bottom - r); path.lineTo(left, bottom - c)
        canvas.drawPath(path, stroke)

        // Сканирующая линия
        if (s.faceFound) {
            val sy = top + (bottom - top) * scanY
            stroke.strokeWidth = dp(1.4f)
            stroke.alpha = 90
            stroke.color = color
            canvas.drawLine(left + dp(6f), sy, right - dp(6f), sy, stroke)
            fill.color = color
            fill.alpha = 160
            canvas.drawCircle(right - dp(6f), sy, dp(2.2f), fill)
        }
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, fw: Float, fh: Float, color: Int, s: FaceSnapshot) {
        val open = s.ear.coerceIn(0f, 1f)
        val capH = fh * 0.13f

        // Реальные позиции глаз из ML Kit (если есть) — иначе симметричные якоря
        val anchors: List<Pair<Float, Float>> = if (s.eyeContours.size >= 2) {
            s.eyeContours.map { pts ->
                var sx = 0f
                var sy = 0f
                var n = 0
                var i = 0
                while (i + 1 < pts.size) {
                    sx += pts[i]; sy += pts[i + 1]; n++
                    i += 2
                }
                val nx = if (n > 0) sx / n else 0.5f
                val ny = if (n > 0) sy / n else 0.4f
                (if (mirrored) 1f - nx else nx) * width to ny * height
            }
        } else {
            val eyeDx = fw * 0.21f
            val eyeY = cy - fh * 0.10f
            listOf((cx - eyeDx) to eyeY, (cx + eyeDx) to eyeY)
        }

        // Контуры глаз
        if (s.eyeContours.size >= 2) {
            stroke.color = color
            stroke.strokeWidth = dp(1.3f)
            stroke.alpha = 170
            for (pts in s.eyeContours) {
                val path = Path()
                var i = 0
                var first = true
                while (i + 1 < pts.size) {
                    val x = (if (mirrored) 1f - pts[i] else pts[i]) * wPx()
                    val y = pts[i + 1] * hPx()
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    i += 2
                }
                path.close()
                canvas.drawPath(path, stroke)
            }
            stroke.alpha = 255
        }

        for ((ex, eyeY) in anchors) {
            val capW = dp(9f)
            val capLeft = ex - capW / 2
            val capTop = eyeY - capH / 2
            val capBottom = eyeY + capH / 2
            fill.color = Color.parseColor("#66101826")
            canvas.drawRoundRect(capLeft, capTop, capLeft + capW, capBottom, capW / 2, capW / 2, fill)
            val openH = capH * max(open, 0.08f)
            fill.color = color
            fill.alpha = 235
            canvas.drawRoundRect(
                capLeft + dp(1.2f),
                capBottom - openH,
                capLeft + capW - dp(1.2f),
                capBottom,
                capW / 2,
                capW / 2,
                fill
            )
            fill.alpha = 255

            stroke.color = color
            stroke.alpha = 170
            stroke.strokeWidth = dp(1.4f)
            val t = dp(5f)
            canvas.drawLine(ex - t, eyeY - capH / 2 - dp(8f), ex + t, eyeY - capH / 2 - dp(8f), stroke)
            canvas.drawLine(ex, eyeY - capH / 2 - dp(11f), ex, eyeY - capH / 2 - dp(5f), stroke)
            stroke.alpha = 255
        }

        text.color = color
        text.textSize = dp(10f)
        text.alpha = 190
        val label = "ГЛАЗА  ${(open * 100).toInt()}%"
        canvas.drawText(label, cx - text.measureText(label) / 2, cy + fh * 0.52f, text)
        text.alpha = 255
    }

    private fun wPx() = width.toFloat()
    private fun hPx() = height.toFloat()

    private fun drawHeadGauge(canvas: Canvas, cx: Float, y: Float, halfW: Float, s: FaceSnapshot) {
        // Шкала наклона головы: центр = прямо, влево/вправо — отклонение
        val value = (s.pitchDeg / 40f).coerceIn(-1f, 1f)
        stroke.color = Color.parseColor("#33425A")
        stroke.strokeWidth = dp(2f)
        stroke.alpha = 255
        val left = cx - halfW
        val right = cx + halfW
        canvas.drawLine(left, y, right, y, stroke)
        // деления
        for (i in -4..4) {
            val x = cx + halfW * (i / 4f) * 0.5f
            val len = if (i == 0) dp(9f) else dp(5f)
            stroke.alpha = if (i == 0) 160 else 90
            canvas.drawLine(x, y - len, x, y + len, stroke)
        }
        stroke.alpha = 255
        // маркер
        val mx = cx + halfW * value * 0.5f
        fill.color = when {
            abs(s.pitchDeg) > 25f -> danger
            abs(s.pitchDeg) > 15f -> warn
            else -> ok
        }
        canvas.drawCircle(mx, y, dp(5f), glow)
        canvas.drawCircle(mx, y, dp(4f), fill)
        text.color = Color.parseColor("#9AABC9")
        text.textSize = dp(9f)
        canvas.drawText("HEAD ${s.pitchDeg.toInt()}°", cx - dp(30f), y - dp(14f), text)
    }

    private fun drawStatsPills(canvas: Canvas, cx: Float, y: Float, s: FaceSnapshot, color: Int) {
        val eyeClosed = s.eyesClosedMs / 1000f
        val label = when {
            eyeClosed > 0.05f -> "ГЛАЗА ЗАКРЫТЫ ${String.format("%.1f", eyeClosed)} с"
            s.faceFound -> "ВОДИТЕЛЬ ВНИМАТЕЛЕН"
            else -> "ПОИСК ЛИЦА"
        }
        text.textSize = dp(11f)
        text.color = if (eyeClosed > 0.05f) danger else Color.parseColor("#F2F6FF")
        val tw = text.measureText(label)
        val padH = dp(12f)
        val padV = dp(7f)
        fill.color = Color.parseColor("#99101826")
        val top = y - dp(10f)
        canvas.drawRoundRect(cx - tw / 2 - padH, top, cx + tw / 2 + padH, top + dp(20f) + padV, dp(10f), dp(10f), fill)
        stroke.color = color
        stroke.alpha = 90
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(cx - tw / 2 - padH, top, cx + tw / 2 + padH, top + dp(20f) + padV, dp(10f), dp(10f), stroke)
        stroke.alpha = 255
        fill.color = color
        canvas.drawCircle(cx - tw / 2 - padH + dp(7f), top + dp(10f) + padV / 2, dp(3f), fill)
        canvas.drawText(label, cx - tw / 2 + dp(6f), top + dp(14f) + padV / 2, text)
    }

    private fun drawSearching(canvas: Canvas, cx: Float, cy: Float, w: Float) {
        val t = (System.currentTimeMillis() % 2400) / 2400f
        stroke.color = warn
        stroke.alpha = 150
        stroke.strokeWidth = dp(1.6f)
        for (i in 0 until 3) {
            val r = dp(28f) + i * dp(26f) * ((t + i / 3f) % 1f)
            stroke.alpha = (150 * (1f - (r / (dp(110f)))).coerceIn(0f, 1f)).toInt()
            canvas.drawCircle(cx, cy, r, stroke)
        }
        stroke.alpha = 255
        text.color = Color.parseColor("#FFB020")
        text.textSize = dp(12f)
        val label = "ПРИВЕДИТЕ КАМЕРУ К ЛИЦУ"
        canvas.drawText(label, cx - text.measureText(label) / 2, cy + dp(70f), text)
    }
}
