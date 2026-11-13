package com.driverguard.eyetrack.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.driverguard.eyetrack.R
import com.driverguard.eyetrack.core.FaceSnapshot
import com.driverguard.eyetrack.core.MonitorBus
import com.driverguard.eyetrack.core.MonitorEvent
import com.driverguard.eyetrack.core.SessionStore
import com.driverguard.eyetrack.core.SettingsStore
import com.driverguard.eyetrack.databinding.ActivityMainBinding
import com.driverguard.eyetrack.databinding.ViewMonitorBinding
import com.driverguard.eyetrack.databinding.ViewWelcomeBinding
import com.driverguard.eyetrack.service.MonitoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Экран-хост: welcome → (разрешение камеры) → наблюдение.
 *
 * Камеру для превью держит сам Activity (CameraX Preview), анализ и тревога —
 * в [MonitoringService]. Это позволяет UI пересоздаться, не роняя мониторинг.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var monitor: ViewMonitorBinding? = null
    private var welcome: ViewWelcomeBinding? = null

    private val settings by lazy { SettingsStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var tripStartMs = 0L
    private var alarmCount = 0
    private var microSleeps = 0
    private var nodCount = 0
    private var yawnCount = 0
    private var lastSnapshot: FaceSnapshot? = null
    private var lookingSinceMs = 0L

    // ------------------------------------------------------------------ bus

    private val faceListener: (FaceSnapshot) -> Unit = { snap ->
        lastSnapshot = snap
        val m = monitor
        if (m != null) {
            m.overlay.snapshot = snap
            val level = when {
                snap.faceFound && snap.eyesClosedMs > 300 -> 2
                !snap.faceFound -> 1
                else -> 0
            }
            m.overlay.level = level
            updateStatus(snap)
        }
    }

    private val eventListener: (MonitorEvent) -> Unit = { e ->
        val m = monitor
        if (m != null) {
            when (e) {
                is MonitorEvent.AlarmStarted -> {
                    alarmCount++
                    m.overlay.level = 3
                }
                is MonitorEvent.Drowsy -> {
                    if (e.level >= 2) {
                        when (e.reason) {
                            MonitorEvent.Reason.YAWN -> yawnCount++
                            MonitorEvent.Reason.HEAD_NOD, MonitorEvent.Reason.MIXED -> nodCount++
                            else -> microSleeps++
                        }
                        m.overlay.level = if (e.level >= 3) 3 else 2
                    }
                }
                else -> Unit
            }
            m.statAlarms.text = alarmCount.toString()
            m.statNods.text = nodCount.toString()
            m.statYawns.text = yawnCount.toString()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cam = granted[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cam) {
            startSession()
        } else {
            Toast.makeText(this, R.string.toast_camera_required, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (hasCameraPermission()) startSession() else showWelcome()

        // Скрытый самотест для проверки тревоги: adb shell am start ... --es debugAlarm 1
        if (intent?.getStringExtra("debugAlarm") == "1") {
            startActivity(
                Intent(this, AlarmActivity::class.java).apply {
                    putExtra(AlarmActivity.EXTRA_REASON, MonitorEvent.Reason.EYES_CLOSED.name)
                }
            )
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showWelcome() {
        binding.content.removeAllViews()
        binding.bottomSheet.isVisible = false
        monitor = null
        val w = ViewWelcomeBinding.inflate(layoutInflater, binding.content, true)
        welcome = w
        w.btnStartOnboarding.setOnClickListener { askPermissions() }
        w.btnLater.setOnClickListener {
            Toast.makeText(this, R.string.toast_later, Toast.LENGTH_SHORT).show()
        }
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startSession() {
        if (MonitorBus.running && monitor != null) return
        binding.content.removeAllViews()
        binding.bottomSheet.isVisible = false
        welcome = null

        val m = ViewMonitorBinding.inflate(layoutInflater, binding.content, true)
        monitor = m
        m.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        m.btnStop.setOnClickListener { finishTrip() }

        bindCamera(m.previewView)
        MonitorBus.addFaceListener(faceListener)
        MonitorBus.addEventListener(eventListener)

        alarmCount = 0
        microSleeps = 0
        nodCount = 0
        yawnCount = 0
        tripStartMs = System.currentTimeMillis()
        lookingSinceMs = tripStartMs

        if (!MonitorBus.running) MonitoringService.start(this)

        monitor!!.overlay.snapshot = lastSnapshot
        startTicker()
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val m = monitor ?: break
                val elapsed = System.currentTimeMillis() - tripStartMs
                val min = elapsed / 60000
                val sec = (elapsed / 1000) % 60
                m.elapsedValue.text = String.format(Locale.US, "%02d:%02d", min, sec)

                val snap = lastSnapshot
                val fatigue = computeFatigue(snap)
                m.fatigueBar.animateTo(fatigue)
                m.fatigueValue.text = "${(fatigue * 100).toInt()}%"
                m.statAlarms.text = alarmCount.toString()
                m.statNods.text = nodCount.toString()
                m.statYawns.text = yawnCount.toString()
                delay(400)
            }
        }
    }

    /**
     * РРЅРґРµРєСЃ СѓСЃС‚Р°Р»РѕСЃС‚Рё РґР»СЏ UI: РєРѕРјР±РёРЅР°С†РёСЏ РѕС‚РєСЂС‹С‚РѕСЃС‚Рё РіР»Р°Р·,
     * времени закрытия и продолжительности пути без остановки.
     */
    private fun computeFatigue(snap: FaceSnapshot?): Float {
        val earPart = if (snap != null && snap.faceFound) ((0.45f - snap.ear) / 0.45f).coerceIn(0f, 1f) else 0f
        val closedPart = ((snap?.eyesClosedMs ?: 0L) / 4000f).coerceIn(0f, 1f)
        val timePart = ((System.currentTimeMillis() - tripStartMs) / (2f * 60 * 60 * 1000)).coerceIn(0f, 0.25f)
        return (earPart * 0.35f + closedPart * 0.45f + timePart).coerceIn(0f, 1f)
    }

    private fun updateStatus(snap: FaceSnapshot) {
        val m = monitor ?: return
        val (textRes, colorRes) = when {
            !snap.faceFound -> R.string.status_searching to R.color.warn
            snap.eyesClosedMs > 300 -> R.string.status_eyes_closed to R.color.danger
            else -> R.string.status_ok to R.color.ok
        }
        m.tripStatus.setText(textRes)
        m.tripStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun bindCamera(view: PreviewView) {
        view.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            }.onFailure { err ->
                Toast.makeText(this, getString(R.string.toast_camera_error, err.message), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishTrip() {
        val elapsed = System.currentTimeMillis() - tripStartMs
        MonitoringService.stop(this)
        MonitorBus.setRunning(false)
        if (elapsed > 15_000) {
            SessionStore(this).add(
                SessionStore.Session(
                    startedAt = tripStartMs,
                    durationMs = elapsed,
                    driveKm = elapsed / 60000f * 0.8f,
                    alarms = alarmCount,
                    microSleeps = microSleeps,
                    nods = nodCount,
                    yawns = yawnCount,
                    attentionPercent = (100f - microSleeps * 2f).coerceIn(0f, 100f),
                )
            )
        }
        stopBindings()
        showSummary(elapsed)
    }

    private fun showSummary(elapsed: Long) {
        binding.content.removeAllViews()
        binding.bottomSheet.isVisible = true
        val hours = elapsed / 3600000
        val mins = (elapsed / 60000) % 60
        val duration = if (hours > 0) "${hours} С‡ ${mins} РјРёРЅ" else "${mins} РјРёРЅ"
        binding.sheetTitle.text = getString(R.string.trip_finished_title)
        binding.sheetSubtitle.text = getString(R.string.trip_finished_summary, duration, alarmCount)
        binding.statusText.text = getString(R.string.trip_finished_stats, nodCount, yawnCount)
        binding.btnPrimary.text = getString(R.string.btn_new_trip)
        binding.btnPrimary.setOnClickListener { startSession() }
        binding.btnSecondary.text = getString(R.string.btn_history)
        binding.btnSecondary.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun stopBindings() {
        tickJob?.cancel()
        tickJob = null
        MonitorBus.removeFaceListener(faceListener)
        MonitorBus.removeEventListener(eventListener)
        cameraProvider?.unbindAll()
        cameraProvider = null
        lastSnapshot = null
    }

    override fun onStart() {
        super.onStart()
        // Если сервис уже работает (вернулись из настроек) — подцепляемся заново
        if (monitor == null && MonitorBus.running && hasCameraPermission()) {
            startSession()
        }
    }

    override fun onStop() {
        super.onStop()
        tickJob?.cancel()
        tickJob = null
        MonitorBus.removeFaceListener(faceListener)
        MonitorBus.removeEventListener(eventListener)
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
