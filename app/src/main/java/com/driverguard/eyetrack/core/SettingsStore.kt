package com.driverguard.eyetrack.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "driver_guard_settings")

/**
 * Настройки чувствительности и калибровки.
 * Значения по умолчанию подобраны под типичный смартфон на держателе
 * на уровне руля/торпедо (камера смотрит чуть снизу вверх на водителя).
 */
class SettingsStore(private val context: Context) {

    data class Settings(
        /** Порог «глаза закрыты»: EAR, ниже которого глаз считается закрытым */
        val earClosedThreshold: Float = 0.13f,
        /** Порог открытых глаз (гистерезис сверху) */
        val earOpenThreshold: Float = 0.17f,
        /** Сколько мс глаза должны быть закрыты до тревоги */
        val eyesClosedAlarmMs: Long = 1100L,
        /** Микро-сон: предупреждение */
        val eyesClosedWarnMs: Long = 500L,
        /** Наклон головы (кивок) в градусах до тревоги */
        val nodAngleDeg: Float = 21f,
        /** Длительность кивка до тревоги */
        val nodHoldMs: Long = 1500L,
        /** Порог открытия рта для зевка */
        val yawnMouthThreshold: Float = 0.42f,
        /** Громкость тревоги 0..1 */
        val alarmVolume: Float = 1f,
        /** Вибрация при тревоге */
        val vibrationEnabled: Boolean = true,
        /** Звук в наушниках/динамик */
        val soundEnabled: Boolean = true,
        /** Периодическое «встряхивание» при засыпании (когда глаза закрыты долго) */
        val requireAttentionCheck: Boolean = true,
        /** Светодиодная подсветка/скрин при тревоге */
        val flashEnabled: Boolean = true,
        /** Калибровка: базовый наклон головы */
        val baselinePitchDeg: Float = 0f,
        val calibrated: Boolean = false,
        /** Показывать отладочный оверлей контуров */
        val debugOverlay: Boolean = true,
    )

    private object Keys {
        val EAR_CLOSED = floatPreferencesKey("ear_closed")
        val EAR_OPEN = floatPreferencesKey("ear_open")
        val EYES_ALARM_MS = longPreferencesKey("eyes_alarm_ms")
        val EYES_WARN_MS = longPreferencesKey("eyes_warn_ms")
        val NOD_ANGLE = floatPreferencesKey("nod_angle")
        val NOD_HOLD_MS = longPreferencesKey("nod_hold_ms")
        val YAWN = floatPreferencesKey("yawn_mouth")
        val VOLUME = floatPreferencesKey("alarm_volume")
        val VIBRATION = booleanPreferencesKey("vibration")
        val SOUND = booleanPreferencesKey("sound")
        val ATTENTION_CHECK = booleanPreferencesKey("attention_check")
        val FLASH = booleanPreferencesKey("flash")
        val BASELINE_PITCH = floatPreferencesKey("baseline_pitch")
        val CALIBRATED = booleanPreferencesKey("calibrated")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            earClosedThreshold = p[Keys.EAR_CLOSED] ?: d.earClosedThreshold,
            earOpenThreshold = p[Keys.EAR_OPEN] ?: d.earOpenThreshold,
            eyesClosedAlarmMs = p[Keys.EYES_ALARM_MS] ?: d.eyesClosedAlarmMs,
            eyesClosedWarnMs = p[Keys.EYES_WARN_MS] ?: d.eyesClosedWarnMs,
            nodAngleDeg = p[Keys.NOD_ANGLE] ?: d.nodAngleDeg,
            nodHoldMs = p[Keys.NOD_HOLD_MS] ?: d.nodHoldMs,
            yawnMouthThreshold = p[Keys.YAWN] ?: d.yawnMouthThreshold,
            alarmVolume = p[Keys.VOLUME] ?: d.alarmVolume,
            vibrationEnabled = p[Keys.VIBRATION] ?: d.vibrationEnabled,
            soundEnabled = p[Keys.SOUND] ?: d.soundEnabled,
            requireAttentionCheck = p[Keys.ATTENTION_CHECK] ?: d.requireAttentionCheck,
            flashEnabled = p[Keys.FLASH] ?: d.flashEnabled,
            baselinePitchDeg = p[Keys.BASELINE_PITCH] ?: d.baselinePitchDeg,
            calibrated = p[Keys.CALIBRATED] ?: d.calibrated,
            debugOverlay = p[Keys.DEBUG_OVERLAY] ?: d.debugOverlay,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun update(block: (Settings) -> Settings) {
        val s = block(current())
        context.dataStore.edit { p ->
            p[Keys.EAR_CLOSED] = s.earClosedThreshold
            p[Keys.EAR_OPEN] = s.earOpenThreshold
            p[Keys.EYES_ALARM_MS] = s.eyesClosedAlarmMs
            p[Keys.EYES_WARN_MS] = s.eyesClosedWarnMs
            p[Keys.NOD_ANGLE] = s.nodAngleDeg
            p[Keys.NOD_HOLD_MS] = s.nodHoldMs
            p[Keys.YAWN] = s.yawnMouthThreshold
            p[Keys.VOLUME] = s.alarmVolume
            p[Keys.VIBRATION] = s.vibrationEnabled
            p[Keys.SOUND] = s.soundEnabled
            p[Keys.ATTENTION_CHECK] = s.requireAttentionCheck
            p[Keys.FLASH] = s.flashEnabled
            p[Keys.BASELINE_PITCH] = s.baselinePitchDeg
            p[Keys.CALIBRATED] = s.calibrated
            p[Keys.DEBUG_OVERLAY] = s.debugOverlay
        }
    }

    /**
     * Единый ползунок «Чувствительность» 0..1 мапится в пороги:
     * 1.0 — максимально чувствительно (срабатывает быстрее и при меньшем кивке),
     * 0.0 — терпимо к мелким проявлениям.
     */
    suspend fun setSensitivity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        update {
            it.copy(
                earClosedThreshold = lerp(0.15f, 0.115f, v),
                earOpenThreshold = lerp(0.19f, 0.155f, v),
                eyesClosedAlarmMs = lerp(1800f, 700f, v).toLong(),
                eyesClosedWarnMs = lerp(800f, 350f, v).toLong(),
                nodAngleDeg = lerp(28f, 16f, v),
                nodHoldMs = lerp(2200f, 900f, v).toLong(),
                yawnMouthThreshold = lerp(0.52f, 0.36f, v),
            )
        }
        context.dataStore.edit { p -> p[Keys.SENSITIVITY] = v }
    }

    suspend fun sensitivity(): Float = context.dataStore.data.first()[Keys.SENSITIVITY] ?: 0.5f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
