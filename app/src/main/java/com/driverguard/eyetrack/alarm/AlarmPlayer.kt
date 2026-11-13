package com.driverguard.eyetrack.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Комбо-тревога: многотональная сирена, поднимающаяся до максимума громкости
 * будильника, и ритмичная вибрация.
 *
 * Звук не зависит от рингтона: он синтезируется один раз (PCM → WAV) и
 * проигрывается через SoundPool как USAGE_ALARM.
 */
class AlarmPlayer(private val context: Context) {

    @Volatile private var started = false
    private var soundPool: SoundPool? = null
    private var streamId = 0
    private var prevVolume = -1
    private val amp: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        v
    }

    fun start(durationMs: Int = 1500, vibration: Boolean = true, volume: Float = 1f) {
        if (started) return
        started = true
        val sp = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool = sp
        val wav = SirenTone.ensureWavFile(context, durationMs)
        val id = sp.load(wav.absolutePath, 1)
        sp.setOnLoadCompleteListener { pool, sampleId, status ->
            if (status == 0 && started && sampleId == id) {
                boostAlarmVolume()
                streamId = pool.play(
                    id,
                    volume.coerceIn(0f, 1f),
                    volume.coerceIn(0f, 1f),
                    100,
                    -1,
                    1f
                )
            }
        }
        if (vibration) startVibration()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            soundPool?.stop(streamId)
            soundPool?.release()
        }
        soundPool = null
        runCatching { vibrator?.cancel() }
        restoreVolume()
    }

    private fun boostAlarmVolume() {
        val a = amp ?: return
        runCatching {
            val current = a.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = a.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                if (prevVolume < 0) prevVolume = current
                a.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    private fun restoreVolume() {
        val a = amp ?: return
        val v = prevVolume
        prevVolume = -1
        if (v >= 0) runCatching { a.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    private fun startVibration() {
        val vib = vibrator ?: return
        val pattern = longArrayOf(0, 280, 110, 280, 110, 560, 180)
        runCatching { vib.vibrate(VibrationEffect.createWaveform(pattern, 0)) }
    }

    object SirenTone {
        private const val SAMPLE_RATE = 16000

        fun ensureWavFile(context: Context, durationMs: Int): File {
            val f = File(context.filesDir, "alarm_$durationMs.wav")
            if (!f.exists() || f.length() < 1000) {
                f.writeBytes(buildWav(durationMs))
            }
            return f
        }

        fun buildWav(durationMs: Int): ByteArray {
            val n = SAMPLE_RATE * durationMs / 1000
            val pcm = ShortArray(n)
            val segLen = SAMPLE_RATE * 220 / 1000 // 220 мс на тон
            for (i in 0 until n) {
                val seg = i / segLen
                val freq = if (seg % 2 == 0) 880.0 else 1320.0
                val posInSeg = (i % segLen).toDouble() / segLen
                // плавное включение/выключение, чтобы не было щелчков
                val env = min(1.0, min(posInSeg * 14, (1 - posInSeg) * 14))
                val t = i.toDouble() / SAMPLE_RATE
                val s = sin(2 * PI * freq * t) * 0.85 + sin(4 * PI * freq * t) * 0.12
                pcm[i] = (s * env * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
            return toWav(pcm)
        }

        private fun toWav(pcm: ShortArray): ByteArray {
            val dataLen = pcm.size * 2
            val out = java.io.ByteArrayOutputStream(44 + dataLen)
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun le16(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }
            out.write("RIFF".toByteArray())
            le32(36 + dataLen)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            le32(16)
            le16(1) // PCM
            le16(1) // mono
            le32(SAMPLE_RATE)
            le32(SAMPLE_RATE * 2)
            le16(2)
            le16(16)
            out.write("data".toByteArray())
            le32(dataLen)
            for (s in pcm) le16(s.toInt())
            return out.toByteArray()
        }
    }
}
