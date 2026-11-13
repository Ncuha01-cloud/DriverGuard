package com.driverguard.eyetrack.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.driverguard.eyetrack.R
import com.driverguard.eyetrack.core.MonitorEvent
import com.driverguard.eyetrack.databinding.ActivityAlarmBinding
import com.driverguard.eyetrack.service.MonitoringService
import java.util.Locale

class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { runCatching { MonitorEvent.Reason.valueOf(it) }.getOrNull() }
            ?: MonitorEvent.Reason.EYES_CLOSED

        if (intent.action == ACTION_DISMISS) {
            finish()
        }

        binding.alarmReason.text = when (reason) {
            MonitorEvent.Reason.EYES_CLOSED -> getString(R.string.alarm_reason_eyes, "1.1")
            MonitorEvent.Reason.HEAD_NOD -> getString(R.string.alarm_reason_nod, "25")
            MonitorEvent.Reason.YAWN -> getString(R.string.alarm_reason_yawn)
            else -> getString(R.string.alarm_reason_mixed)
        }
        binding.alarmTitle.text = getString(R.string.alarm_title)

        binding.btnWake.setOnClickListener {
            MonitoringService.stopAlarm(this)
            finish()
        }
        binding.btnStopTrip.setOnClickListener {
            MonitoringService.stopAlarm(this)
            MonitoringService.stop(this)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { MonitoringService.stopAlarm(this) }
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val ACTION_DISMISS = "com.driverguard.eyetrack.DISMISS_ALARM"

        /** Закрывает открытый экран тревоги (вызывается сервисом при снятии сигнала). */
        fun dismiss(ctx: android.content.Context) {
            val i = Intent(ctx, AlarmActivity::class.java).apply {
                action = ACTION_DISMISS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(i) }
        }
    }
}
