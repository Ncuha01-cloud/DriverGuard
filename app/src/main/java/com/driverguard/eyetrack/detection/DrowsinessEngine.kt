package com.driverguard.eyetrack.detection

import com.driverguard.eyetrack.core.MonitorEvent.Reason
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Детерминированное «ядро» детектора усталости.
 *
 * Не зависит от Android/ML Kit, поэтому легко тестируется и объясняется:
 *  - микро-сон / закрытие глаз: время непрерывного закрытия с гистерезисом;
 *  - кивок головы: угол относительно откалиброванного базиса + таймер удержания;
 *  - зевота: длительная фаза открытого рта;
 *  - накопленная усталость (0..1): скользящая средняя «плохого» поведения.
 *
 * Результат — "score" (насколько человек сонлив) и уровень 1..3 для UI.
 */
class DrowsinessEngine {

    data class Config(
        val earClosed: Float = 0.13f,
        val earOpen: Float = 0.17f,
        val eyesAlarmMs: Long = 1100L,
        val eyesWarnMs: Long = 500L,
        val nodAngleDeg: Float = 21f,
        val nodHoldMs: Long = 1500L,
        val yawnThreshold: Float = 0.42f,
        val baselinePitchDeg: Float = 0f,
    )

    data class Output(
        val level: Int,
        val reason: Reason?,
        val fatigueScore: Float,
        val eyesClosedMs: Long,
        val nodMs: Long,
        val yawnMs: Long,
        val avgEar: Float,
        val headPitch: Float,
        val glance: Boolean,
        val faceLostMs: Long,
        val attentionLossRatio: Float,
    )

    private var cfg = Config()
    private var eyesClosedSince = -1L
    private var eyesOpenSince = -1L
    private var nodSince = -1L
    private var yawnSince = -1L
    private var faceLostSince = -1L

    private var earAvg = 0.28f
    private var pitchAvg = 0f
    private var fatigue = 0f
    private val pitchHistory = ArrayDeque<Float>()
    private var badFrames = 0
    private var totalFrames = 0

    /** Сколько раз за сессию «провалились» в микро-сон — используется для плавного роста усталости */
    private var microSleepCount = 0

    fun configure(cfg: Config) {
        this.cfg = cfg
    }

    fun reset() {
        eyesClosedSince = -1L
        eyesOpenSince = -1L
        nodSince = -1L
        yawnSince = -1L
        faceLostSince = -1L
        earAvg = 0.28f
        pitchAvg = 0f
        fatigue = 0f
        pitchHistory.clear()
        badFrames = 0
        totalFrames = 0
        microSleepCount = 0
    }

    /**
     * @param ear открытость глаз 0..1 (из ML Kit контуров); NaN если нет данных
     * @param mouthRatio высота рта / ширина лица (0..1.2, ~0.03 закрыт, >0.4 зевает)
     * @param smileProbability ML Kit (не используется напрямую, оставлено для будущего)
     */
    fun update(
        nowMs: Long,
        faceFound: Boolean,
        ear: Float,
        pitchDeg: Float,
        yawDeg: Float,
        rollDeg: Float,
        mouthRatio: Float,
    ): Output {
        totalFrames++

        if (!faceFound) {
            if (faceLostSince < 0) faceLostSince = nowMs
            val lost = nowMs - faceLostSince
            // Потеря лица более 2.5с (взгляд в окно/зеркало) не считается усталостью,
            // но и не считается вниманием.
            badFrames += if (lost > 2500) 1 else 0
            val out = Output(
                level = 1,
                reason = Reason.EYES_ENGAGED_CHECK,
                fatigueScore = fatigue,
                eyesClosedMs = 0,
                nodMs = 0,
                yawnMs = 0,
                avgEar = earAvg,
                headPitch = 0f,
                glance = false,
                faceLostMs = lost,
                attentionLossRatio = if (totalFrames == 0) 0f else badFrames.toFloat() / totalFrames,
            )
            return out
        }
        faceLostSince = -1L

        // ---- глаза ----
        val hasEar = !ear.isNaN()
        if (hasEar) earAvg = earAvg * 0.78f + ear * 0.22f
        else earAvg = earAvg * 0.95f + 0.28f * 0.05f

        val effectiveEar = if (hasEar) ear else earAvg
        val closed = effectiveEar < cfg.earClosed
        val clearlyOpen = effectiveEar > cfg.earOpen

        if (closed) {
            if (eyesClosedSince < 0) eyesClosedSince = nowMs
            eyesOpenSince = -1L
        } else if (clearlyOpen) {
            if (eyesClosedSince >= 0) {
                val dur = nowMs - eyesClosedSince
                if (dur >= cfg.eyesWarnMs) microSleepCount++
            }
            eyesClosedSince = -1L
            if (eyesOpenSince < 0) eyesOpenSince = nowMs
        }
        val eyesClosedMs = if (eyesClosedSince >= 0) nowMs - eyesClosedSince else 0L

        // ---- голова ----
        val relPitch = pitchDeg - cfg.baselinePitchDeg
        pitchHistory.addLast(relPitch)
        while (pitchHistory.size > 30) pitchHistory.removeFirst()
        pitchAvg = pitchHistory.average().toFloat()
        // Кивок = длительный наклон вперёд. Используем сглаженный угол,
        // чтобы одиночный кадр-блик не вызывал тревогу.
        if (pitchAvg > cfg.nodAngleDeg) {
            if (nodSince < 0) nodSince = nowMs
        } else if (pitchAvg < cfg.nodAngleDeg * 0.7f) {
            nodSince = -1L
        }
        val nodMs = if (nodSince >= 0) nowMs - nodSince else 0L

        // ---- зевота ----
        if (mouthRatio > cfg.yawnThreshold) {
            if (yawnSince < 0) yawnSince = nowMs
        } else if (mouthRatio < cfg.yawnThreshold * 0.7f) {
            yawnSince = -1L
        }
        val yawnMs = if (yawnSince >= 0) nowMs - yawnSince else 0L

        // Взгляд в сторону больше ~22° — водитель отвлёкся, но это не усталость.
        val glance = abs(yawDeg) > 22f || abs(rollDeg) > 18f

        // ---- накопленная усталость ----
        var bad = 0f
        bad += clamp01((0.24f - earAvg) / 0.10f) * 0.45f
        bad += clamp01(relPitch / max(cfg.nodAngleDeg, 1f)) * 0.30f
        bad += clamp01((mouthRatio - cfg.yawnThreshold * 0.6f) / (cfg.yawnThreshold * 0.8f)) * 0.15f
        bad += clamp01(microSleepCount / 6f) * 0.10f
        if (bad > 0.45f) badFrames++
        fatigue = fatigue * 0.985f + bad * 0.015f

        // ---- уровни ----
        var level = 0
        var reason: Reason? = null

        if (eyesClosedMs >= cfg.eyesAlarmMs) {
            level = 3; reason = Reason.EYES_CLOSED
        } else if (nodMs >= cfg.nodHoldMs) {
            level = 3; reason = Reason.HEAD_NOD
        } else if (yawnMs >= 1400L) {
            level = 2; reason = Reason.YAWN
        } else if (eyesClosedMs >= cfg.eyesWarnMs) {
            level = 1; reason = Reason.EYES_CLOSED
        } else if (nodMs >= cfg.nodHoldMs * 0.6f) {
            level = 1; reason = Reason.HEAD_NOD
        } else if (yawnMs >= 700L) {
            level = 1; reason = Reason.YAWN
        }

        // Смешанный сценарий (сонный + смотрит вниз) ускоряет тревогу.
        if (level == 3 && fatigue > 0.6f && reason != Reason.EYES_CLOSED) reason = Reason.MIXED

        return Output(
            level = level,
            reason = reason,
            fatigueScore = min(fatigue * (1f + microSleepCount * 0.05f), 1f),
            eyesClosedMs = eyesClosedMs,
            nodMs = nodMs,
            yawnMs = yawnMs,
            avgEar = earAvg,
            headPitch = relPitch,
            glance = glance,
            faceLostMs = 0L,
            attentionLossRatio = if (totalFrames == 0) 0f else badFrames.toFloat() / totalFrames,
        )
    }

    /**
     * EAR (Eye Aspect Ratio) по контуру из ML Kit.
     *
     * ML Kit возвращает набор точек вокруг глаз; для обоих глаз они идут
     * начиная от внешнего угла к внутреннему. Мы берём 4 равноотстоящие точки
     * контура и считаем отношение «высоты» к «ширине» контура глаза.
     * Результат масштабируется перцентилями по истории, поэтому устойчив
     * к разному разрешению/углу камеры.
     */
    object Eyes {
        fun rawRatio(points: List<FloatArray>): Float {
            if (points.size < 4) return Float.NaN
            // Углы глаза
            val p0 = points.first()
            val p1 = points.last()
            val width = dist(p0, p1)
            if (width < 1f) return Float.NaN
            val n = points.size
            val a = points[n / 4]
            val b = points[(3 * n) / 4]
            // Высота по нормали к линии глаза
            val nx = -(p1[1] - p0[1]) / width
            val ny = (p1[0] - p0[0]) / width
            val h1 = abs((a[0] - p0[0]) * nx + (a[1] - p0[1]) * ny)
            val h2 = abs((b[0] - p0[0]) * nx + (b[1] - p0[1]) * ny)
            val ratio = (h1 + h2) / 2f / width
            return if (ratio.isNaN()) Float.NaN else ratio
        }

        fun dist(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Адаптивный нормализатор: переводит сырое отношение контура (0..0.6)
     * в «открытость» 0..1, используя экспоненциальный минимум/максимум.
     * Самообучается за первые ~10 секунд сессии.
     */
    class EarNormalizer {
        private var lo = Float.NaN
        private var hi = Float.NaN

        fun reset() {
            lo = Float.NaN
            hi = Float.NaN
        }

        fun normalize(raw: Float, stronglyOpen: Boolean): Float {
            if (raw.isNaN()) return Float.NaN
            if (lo.isNaN()) {
                lo = raw * 0.55f
                hi = raw * 1.15f
            }
            // минимум — закрытые глаза (убывает быстро, растёт медленно),
            // максимум — широко открытые
            if (raw < lo) lo = lo * 0.90f + raw * 0.10f
            else lo = lo * 0.999f + raw * 0.001f
            if (raw > hi) hi = hi * 0.90f + raw * 0.10f
            else if (stronglyOpen) hi = hi * 0.995f + raw * 0.005f

            val range = max(hi - lo, 0.012f)
            val v = clamp01((raw - lo) / range)
            // Мягкое S-растягивание контраста: типично открытый глаз ~0.72,
            // полуприкрытый ~0.35, закрытый ~0.05.
            return clamp01((v - 0.28f) / 0.62f)
        }
    }

    companion object {
        fun clamp01(v: Float): Float = if (v.isNaN()) 0f else max(0f, min(1f, v))
    }
}
