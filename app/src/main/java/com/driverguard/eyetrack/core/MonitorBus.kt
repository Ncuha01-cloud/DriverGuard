package com.driverguard.eyetrack.core

/**
 * Единая шина между сервисом мониторинга (камера + ML Kit) и UI.
 * Сервис пушит сюда кадры аналитики, экран наблюдения подписывается.
 */
object MonitorBus {

    @Volatile
    var running: Boolean = false
        private set

    private val faceListeners = mutableListOf<(FaceSnapshot) -> Unit>()
    private val eventListeners = mutableListOf<(MonitorEvent) -> Unit>()
    private val stateListeners = mutableListOf<(Boolean) -> Unit>()

    fun setRunning(value: Boolean) {
        running = value
        val snapshot = stateListeners.toList()
        snapshot.forEach { runCatching { it(value) } }
    }

    fun emitFace(snapshot: FaceSnapshot) {
        faceListeners.toList().forEach { runCatching { it(snapshot) } }
    }

    fun emitEvent(event: MonitorEvent) {
        eventListeners.toList().forEach { runCatching { it(event) } }
    }

    fun addFaceListener(l: (FaceSnapshot) -> Unit) = synchronized(faceListeners) { faceListeners += l }
    fun removeFaceListener(l: (FaceSnapshot) -> Unit) = synchronized(faceListeners) { faceListeners -= l }

    fun addEventListener(l: (MonitorEvent) -> Unit) = synchronized(eventListeners) { eventListeners += l }
    fun removeEventListener(l: (MonitorEvent) -> Unit) = synchronized(eventListeners) { eventListeners -= l }

    fun addStateListener(l: (Boolean) -> Unit) = synchronized(stateListeners) { stateListeners += l }
    fun removeStateListener(l: (Boolean) -> Unit) = synchronized(stateListeners) { stateListeners -= l }
}

/**
 * Один кадр аналитики лица.
 *
 * @param ear открытость глаз 0..1 (0 — закрыты, ~0.7 — широко открыты)
 * @param pitchDeg наклон головы вперёд в градусах (положительный — вниз)
 * @param eyesClosedMs сколько миллисекунд глаза непрерывно закрыты
 * @param glance взгляд в сторону / сильный поворот головы
 */
data class FaceSnapshot(
    val faceFound: Boolean,
    val ear: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val rollDeg: Float,
    val mouthOpen: Float,
    val rawEyesLeft: Float,
    val rawEyesRight: Float,
    val eyesClosedMs: Long,
    val faceLostMs: Long = 0L,
    val glance: Boolean = false,
    /** Нормированный bounding box лица [l, t, r, b] в координатах 0..1 */
    val box: FloatArray? = null,
    /** Контуры глаз [leftEye, rightEye] — по 2 массива [x,y,...] нормированных */
    val eyeContours: List<FloatArray> = emptyList(),
)

sealed class MonitorEvent {
    /** Уровни: 1 — мягкое предупреждение, 2 — опасность, 3 — тревога */
    data class Drowsy(val level: Int, val reason: Reason, val score: Float) : MonitorEvent()
    object AlarmStarted : MonitorEvent()
    object AlarmStopped : MonitorEvent()

    enum class Reason { EYES_CLOSED, HEAD_NOD, YAWN, MIXED, EYES_ENGAGED_CHECK }
}
