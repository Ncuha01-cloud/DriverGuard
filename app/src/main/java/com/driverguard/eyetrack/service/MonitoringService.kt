package com.driverguard.eyetrack.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.driverguard.eyetrack.R
import com.driverguard.eyetrack.alarm.AlarmPlayer
import com.driverguard.eyetrack.core.FaceSnapshot
import com.driverguard.eyetrack.core.MonitorBus
import com.driverguard.eyetrack.core.MonitorEvent
import com.driverguard.eyetrack.core.SettingsStore
import com.driverguard.eyetrack.detection.DrowsinessEngine
import com.driverguard.eyetrack.detection.FaceAnalyzer
import com.driverguard.eyetrack.ui.AlarmActivity
import com.driverguard.eyetrack.ui.MainActivity
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновый сервис мониторинга: держит фронтальную камеру (CameraX),
 * гоняет кадры через ML Kit и при усталости поднимает тревогу.
 *
 * Работает как foreground-service с типом `camera`, поэтому Android
 * не убивает его, когда экран погашен или открыт навигатор.
 */
class MonitoringService : LifecycleService() {

    private lateinit var settings: SettingsStore
    private val analyzer = FaceAnalyzer()
    private val engine = DrowsinessEngine()
    private lateinit var alarm: AlarmPlayer

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frameScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val busy = AtomicBoolean(false)
    @Volatile
    private var loggedLayout = false
    private var cfg = SettingsStore.Settings()
    private var lastAlertMs = 0L
    private var lastOutput: DrowsinessEngine.Output? = null
    private var lastFrame: FaceAnalyzer.Frame? = null

    @Volatile
    private var alarmActive = false

    private var alarmCount = 0
    private var nodCount = 0
    private var yawnCount = 0
    private var microSleeps = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(applicationContext)
        alarm = AlarmPlayer(applicationContext)
        startForegroundCompat()
        acquireWakeLock()
        MonitorBus.setRunning(true)

        scope.launch {
            settings.flow.collect { s ->
                cfg = s
                engine.configure(
                    DrowsinessEngine.Config(
                        earClosed = s.earClosedThreshold,
                        earOpen = s.earOpenThreshold,
                        eyesAlarmMs = s.eyesClosedAlarmMs,
                        eyesWarnMs = s.eyesClosedWarnMs,
                        nodAngleDeg = s.nodAngleDeg,
                        nodHoldMs = s.nodHoldMs,
                        yawnThreshold = s.yawnMouthThreshold,
                        baselinePitchDeg = s.baselinePitchDeg,
                    )
                )
            }
        }

        bindCamera()
    }

    // ------------------------------------------------------------------ camera

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(android.util.Size(480, 640))
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy -> handleFrame(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.i(TAG, "camera bound")
            }.onFailure { err ->
                Log.e(TAG, "failed to bind camera", err)
                updateNotification(-1)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(proxy: ImageProxy) {
        // Кадр копируем в независимый ImageReader и отдаём ML Kit — это самый
        // надёжный путь: ML Kit сам умеет читать YUV_420_888 с planes.
        var bitmapEvidence = ""
        val rotation = proxy.imageInfo.rotationDegrees
        val w = proxy.width
        val h = proxy.height
        var image: InputImage? = null
        try {
            // Извлекаем Y (яркость) и собираем Bitmap — универсальный путь,
            // который работает на любых устройствах и эмуляторах.
            val plane = proxy.planes.firstOrNull() ?: error("no planes")
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer
            val y = ByteArray(w * h)
            if (pixelStride == 1 && rowStride == w) {
                (buffer.duplicate().rewind() as ByteBuffer).get(y)
            } else {
                val row = ByteArray(rowStride)
                for (r in 0 until h) {
                    buffer.position(r * rowStride)
                    buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
                    for (c in 0 until w) {
                        y[r * w + c] = row[c * pixelStride]
                    }
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (i in y.indices) {
                val v = y[i].toInt() and 0xFF
                px[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val rotated = if (rotation != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
            } else bmp
            image = InputImage.fromBitmap(rotated, 0)
            bitmapEvidence = "bitmap ${rotated.width}x${rotated.height}"
        } catch (t: Throwable) {
            Log.e(TAG, "frame conversion failed $bitmapEvidence", t)
        } finally {
            proxy.close()
        }
        val img = image ?: return
        if (!busy.compareAndSet(false, true)) return

        frameScope.launch {
            val start = System.currentTimeMillis()
            try {
                val frame = analyzer.analyze(img)
                lastFrame = frame
                onFrame(frame, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.e(TAG, "analyze failed", t)
            } finally {
                busy.set(false)
                val cost = System.currentTimeMillis() - start
                if (cost > 500) Log.w(TAG, "slow frame $cost ms")
            }
        }
    }

    // ------------------------------------------------------------------ engine

    private fun onFrame(frame: FaceAnalyzer.Frame, now: Long) {
        val out = engine.update(
            nowMs = now,
            faceFound = frame.face != null,
            ear = frame.ear,
            pitchDeg = frame.pitch,
            yawDeg = frame.yaw,
            rollDeg = frame.roll,
            mouthRatio = frame.mouthRatio,
        )
        lastOutput = out

        MonitorBus.emitFace(
            FaceSnapshot(
                faceFound = frame.face != null,
                ear = out.avgEar,
                pitchDeg = frame.pitch,
                yawDeg = frame.yaw,
                rollDeg = frame.roll,
                mouthOpen = frame.mouthRatio,
                rawEyesLeft = frame.leftEyeOpenProb ?: Float.NaN,
                rawEyesRight = frame.rightEyeOpenProb ?: Float.NaN,
                eyesClosedMs = out.eyesClosedMs,
                faceLostMs = out.faceLostMs,
                glance = out.glance,
                box = frame.box,
                eyeContours = frame.eyes,
            )
        )

        val reason = out.reason
        if (out.level >= 1 && reason != null) {
            val cooldown = when (out.level) {
                3 -> ALARM_COOLDOWN_MS
                2 -> 3000L
                else -> 5000L
            }
            if (now - lastAlertMs > cooldown) {
                lastAlertMs = now
                when (reason) {
                    MonitorEvent.Reason.HEAD_NOD, MonitorEvent.Reason.MIXED -> nodCount++
                    MonitorEvent.Reason.YAWN -> yawnCount++
                    else -> if (out.level >= 2) microSleeps++
                }
                MonitorBus.emitEvent(MonitorEvent.Drowsy(out.level, reason, out.fatigueScore))
                if (out.level >= 3) fireAlarm(reason) else updateNotification(out.level)
            }
        } else if (alarmActive && out.eyesClosedMs == 0L && out.nodMs == 0L) {
            stopAlarmInternal()
        }
    }

    private fun fireAlarm(reason: MonitorEvent.Reason) {
        alarmCount++
        if (alarmActive) return
        alarmActive = true
        MonitorBus.emitEvent(MonitorEvent.AlarmStarted)
        alarm.start(
            durationMs = 1500,
            vibration = cfg.vibrationEnabled,
            volume = cfg.alarmVolume,
        )
        scope.launch {
            runCatching {
                val i = Intent(this@MonitoringService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(AlarmActivity.EXTRA_REASON, reason.name)
                }
                startActivity(i)
            }
        }
        updateNotification(3)
    }

    fun stopAlarmInternal() {
        if (!alarmActive) return
        alarmActive = false
        runCatching { alarm.stop() }
        MonitorBus.emitEvent(MonitorEvent.AlarmStopped)
        updateNotification(0)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_ALARM -> stopAlarmInternal()
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { alarm.stop() }
        runCatching { cameraProvider?.unbindAll() }
        analyzer.close()
        cameraExecutor.shutdown()
        scope.cancel()
        frameScope.cancel()
        releaseWakeLock()
        MonitorBus.setRunning(false)
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notifications

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_monitoring),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.channel_alarm),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val n = buildNotification(0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(level: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAlarm = PendingIntent.getService(
            this, 1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = when (level) {
            3 -> getString(R.string.notif_alarm_text)
            2 -> getString(R.string.notif_warn_text)
            -1 -> getString(R.string.notif_camera_error)
            else -> getString(R.string.notif_running_text)
        }
        val title = when (level) {
            3 -> getString(R.string.notif_alarm_title)
            2 -> getString(R.string.notif_warn_title)
            0 -> getString(R.string.notif_running_title)
            else -> getString(R.string.notif_running_title)
        }

        val b = NotificationCompat.Builder(this, if (level >= 2) CHANNEL_ALARM else CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(
                if (level >= 2) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                if (level >= 2) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW
            )
        if (level >= 3) b.addAction(0, getString(R.string.action_stop_alarm), stopAlarm)
        b.addAction(0, getString(R.string.action_finish), stop)
        return b.build()
    }

    private fun updateNotification(level: Int) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(level))
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriverGuard:monitor")
                .also { it.acquire(8 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val TAG = "DriverGuard"
        private const val CHANNEL = "monitoring"
        private const val CHANNEL_ALARM = "alarm"
        private const val NOTIF_ID = 42
        private const val ALARM_COOLDOWN_MS = 12_000L

        const val ACTION_STOP_ALARM = "com.driverguard.eyetrack.STOP_ALARM"
        const val ACTION_STOP_SERVICE = "com.driverguard.eyetrack.STOP_SERVICE"

        @Volatile
        private var instance: MonitoringService? = null

        fun canStart(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!canStart(ctx)) return
            MonitorBus.setRunning(true)
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitoringService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitoringService::class.java))
            MonitorBus.setRunning(false)
        }

        fun stopAlarm(ctx: Context) {
            instance?.stopAlarmInternal()
            runCatching {
                ctx.stopService(
                    Intent(ctx, MonitoringService::class.java).setAction(ACTION_STOP_ALARM)
                )
            }
            // Экран тревоги закрываем, даже если сервис уже не жив
            AlarmActivity.dismiss(ctx)
        }

        fun isAlarmActive(): Boolean = instance?.alarmActive == true
    }
}
