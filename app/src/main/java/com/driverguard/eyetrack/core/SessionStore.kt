package com.driverguard.eyetrack.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальная история сессий. Без сети, без аналитики: данные не покидают телефон.
 */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "sessions.json")

    data class Session(
        val startedAt: Long,
        val durationMs: Long,
        val driveKm: Float,
        val alarms: Int,
        val microSleeps: Int,
        val nods: Int,
        val yawns: Int,
        val attentionPercent: Float,
    )

    fun add(session: Session) {
        val all = load().toMutableList()
        all.add(0, session)
        while (all.size > 60) all.removeAt(all.size - 1)
        val arr = JSONArray()
        all.forEach { s ->
            arr.put(
                JSONObject()
                    .put("start", s.startedAt)
                    .put("dur", s.durationMs)
                    .put("km", s.driveKm.toDouble())
                    .put("alarms", s.alarms)
                    .put("micro", s.microSleeps)
                    .put("nods", s.nods)
                    .put("yawns", s.yawns)
                    .put("att", s.attentionPercent.toDouble())
            )
        }
        file.writeText(arr.toString())
    }

    fun load(): List<Session> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(
                    startedAt = o.getLong("start"),
                    durationMs = o.getLong("dur"),
                    driveKm = o.optDouble("km", 0.0).toFloat(),
                    alarms = o.optInt("alarms"),
                    microSleeps = o.optInt("micro"),
                    nods = o.optInt("nods"),
                    yawns = o.optInt("yawns"),
                    attentionPercent = o.optDouble("att", 100.0).toFloat(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun totals(): Session {
        val all = load()
        return Session(
            startedAt = 0,
            durationMs = all.sumOf { it.durationMs },
            driveKm = all.sumOf { it.driveKm.toDouble() }.toFloat(),
            alarms = all.sumOf { it.alarms },
            microSleeps = all.sumOf { it.microSleeps },
            nods = all.sumOf { it.nods },
            yawns = all.sumOf { it.yawns },
            attentionPercent = if (all.isEmpty()) 100f else all.map { it.attentionPercent }.average().toFloat(),
        )
    }

    companion object {
        private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

        fun formatDate(ts: Long): String = fmt.format(Date(ts))

        fun formatDuration(ms: Long): String {
            val totalMin = ms / 60000
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h} ч ${m} мин" else "${m} мин"
        }
    }
}
