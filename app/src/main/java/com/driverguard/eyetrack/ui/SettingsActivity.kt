package com.driverguard.eyetrack.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.driverguard.eyetrack.BuildConfig
import com.driverguard.eyetrack.alarm.AlarmPlayer
import com.driverguard.eyetrack.core.FaceSnapshot
import com.driverguard.eyetrack.core.MonitorBus
import com.driverguard.eyetrack.core.SessionStore
import com.driverguard.eyetrack.core.SettingsStore
import com.driverguard.eyetrack.databinding.ActivitySettingsBinding
import com.driverguard.eyetrack.databinding.ViewSettingsBinding
import kotlinx.coroutines.launch

/**
 * Настройки: чувствительность, калибровка посадки, параметры тревоги,
 * история поездок и приватность.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var view: ViewSettingsBinding
    private lateinit var store: SettingsStore
    // AlarmPlayer трогает Context — создаём только после onCreate
    private val alarm by lazy { AlarmPlayer(this) }
    private val main = Handler(Looper.getMainLooper())

    private var calibrationSamples = ArrayList<Float>()
    private var calibrating = false
    private var suppress = false

    private val calibrationListener: (FaceSnapshot) -> Unit = { snap ->
        if (calibrating && snap.faceFound) {
            calibrationSamples += snap.pitchDeg
            val m = view
            val seconds = (calibrationSamples.size / 10f)
            m.calibrationState.text = "Держите голову прямо… ${seconds.toInt()} с"
            if (calibrationSamples.size >= 45) completeCalibration()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

        binding.btnBack.setOnClickListener { finish() }
        val m = ViewSettingsBinding.inflate(layoutInflater, binding.content, true)
        view = m
        m.tvVersion.text = "DriverGuard ${BuildConfig.VERSION_NAME}"

        lifecycleScope.launch {
            val s = store.current()
            val sens = store.sensitivity()
            suppress = true
            m.sensitivitySlider.value = sens
            m.switchSound.isChecked = s.soundEnabled
            m.switchVibration.isChecked = s.vibrationEnabled
            m.switchOverlay.isChecked = s.debugOverlay
            m.volumeSlider.value = s.alarmVolume
            suppress = false
            updateSensitivityLabel(sens)
            updateThresholdsText(s, sens)
            updateCalibration(s.calibrated, s.baselinePitchDeg)
        }

        m.sensitivitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppress) return@addOnChangeListener
            updateSensitivityLabel(value)
            lifecycleScope.launch {
                store.setSensitivity(value)
                val s = store.current()
                updateThresholdsText(s, value)
            }
        }

        m.switchSound.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            lifecycleScope.launch { store.update { it.copy(soundEnabled = checked) } }
        }
        m.switchVibration.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            lifecycleScope.launch { store.update { it.copy(vibrationEnabled = checked) } }
        }
        m.switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            lifecycleScope.launch { store.update { it.copy(debugOverlay = checked) } }
        }
        m.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppress) return@addOnChangeListener
            lifecycleScope.launch { store.update { it.copy(alarmVolume = value) } }
        }

        m.btnTestAlarm.setOnClickListener {
            alarm.start(durationMs = 1500, vibration = m.switchVibration.isChecked)
            main.postDelayed({ alarm.stop() }, 1600)
            Toast.makeText(this, "Так звучит тревога", Toast.LENGTH_SHORT).show()
        }

        m.btnCalibrate.setOnClickListener { startCalibration() }

        showHistory()

        binding.title.text = getString(com.driverguard.eyetrack.R.string.settings_title)
    }

    private fun updateSensitivityLabel(v: Float) {
        val label = when {
            v < 0.2f -> "Низкая"
            v < 0.45f -> "Ниже средней"
            v < 0.6f -> "Средняя"
            v < 0.85f -> "Высокая"
            else -> "Максимальная"
        }
        view.sensitivityLabel.text = label
    }

    private fun updateThresholdsText(s: SettingsStore.Settings, sens: Float) {
        val eyes = s.eyesClosedAlarmMs / 1000f
        view.thresholdsValue.text = buildString {
            append("Пороги: глаза закрыты ")
            append(String.format(java.util.Locale.US, "%.1f", eyes))
            append(" с · кивок ")
            append(s.nodAngleDeg.toInt())
            append("°")
        }
        view.sensitivityLabel.contentDescription = "Чувствительность ${(sens * 100).toInt()}%"
    }

    private fun startCalibration() {
        if (!MonitorBus.running) {
            Toast.makeText(this, "Сначала запустите поездку и сядьте перед камерой", Toast.LENGTH_LONG).show()
            return
        }
        calibrating = true
        calibrationSamples.clear()
        MonitorBus.addFaceListener(calibrationListener)
        view.calibrationState.text = "Смотрите прямо в камеру…"
        Toast.makeText(this, "Смотрите прямо 5 секунд", Toast.LENGTH_SHORT).show()
        main.postDelayed({
            if (calibrating) {
                completeCalibration()
            }
        }, 6000)
    }

    private fun completeCalibration() {
        if (!calibrating) return
        calibrating = false
        MonitorBus.removeFaceListener(calibrationListener)
        if (calibrationSamples.size < 15) {
            view.calibrationState.text = "Лицо не распознано — попробуйте ещё раз"
            return
        }
        val sorted = calibrationSamples.sorted()
        // медиана — отбрасывает выбросы морганий
        val median = sorted[sorted.size / 2]
        lifecycleScope.launch {
            store.update { it.copy(baselinePitchDeg = median, calibrated = true) }
            updateCalibration(true, median)
        }
    }

    private fun updateCalibration(done: Boolean, base: Float) {
        view.calibrationState.text = if (done) {
            "Калибровка выполнена · нейтральный наклон ${base.toInt()}°"
        } else {
            "Не откалибровано · используется значение по умолчанию"
        }
        view.btnCalibrate.text = if (done) "Калибровать заново" else "Калибровать"
    }

    private fun showHistory() {
        val history = SessionStore(this).load().take(5)
        val totals = SessionStore(this).totals()
        val container = view.historyContainer
        container.removeAllViews()
        if (history.isEmpty()) {
            container.addView(
                textRow("Пока нет завершённых поездок. История хранится только на этом телефоне.")
            )
            return
        }
        container.addView(
            textRow(
                "Всего: ${SessionStore.formatDuration(totals.durationMs)} · " +
                    "${totals.alarms} тревог · ${totals.yawns} зевков · внимание ${totals.attentionPercent.toInt()}%"
            )
        )
        history.forEach { s ->
            container.addView(
                textRow(
                    "${SessionStore.formatDate(s.startedAt)} — ${SessionStore.formatDuration(s.durationMs)}, " +
                        "тревог ${s.alarms}, внимание ${s.attentionPercent.toInt()}%"
                )
            )
        }
    }

    private fun textRow(text: String): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = text
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.driverguard.eyetrack.R.color.ink_secondary))
        tv.textSize = 13f
        tv.setPadding(0, 4, 0, 4)
        return tv
    }

    override fun onDestroy() {
        MonitorBus.removeFaceListener(calibrationListener)
        main.removeCallbacksAndMessages(null)
        alarm.stop()
        super.onDestroy()
    }
}
