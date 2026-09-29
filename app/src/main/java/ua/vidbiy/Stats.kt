package ua.vidbiy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Ваші ночі за останні дні — з історії програми. */
data class NightStats(
    val sessions: Int,
    val nightsWithAlert: Int,
    val rings: Int,
    val snoozes: Int,
    val shifted: Int,
    /** Скільки в середньому хвилин від сигналу до вимкнення; null — немає даних. */
    val ringToStopMin: Int?,
)

/** Тривоги в місці — з історії siren.pp.ua (останні тривоги місця й того, що його охоплює). */
data class PlaceStats(
    val count: Int,
    val count7d: Int,
    val avgMin: Int,
    val longestMin: Int,
    /** Частка тривог, що почалися вночі (00:00–06:00), у відсотках. */
    val nightShare: Int,
    /** О котрій годині найчастіше закінчуються нічні тривоги; null — нічних не було. */
    val nightEndHour: Int?,
    /** Хвилини тривоги за кожен із останніх [DAYS] днів, від найдавнішого; -1 — за цей день даних немає. */
    val perDay: List<Int>,
    val since: Long,
)

object Stats {
    const val DAYS = 14
    private const val PERSONAL_DAYS = 30L

    fun personal(sessions: List<HistorySession>, now: Long = System.currentTimeMillis()): NightStats {
        val recent = sessions.filter { now - it.startedAt <= PERSONAL_DAYS * 24 * 60 * 60_000L }
        val events = recent.flatMap { it.events }
        val delays = recent.mapNotNull { s ->
            val ring = s.events.lastOrNull { it.type == "ring" } ?: return@mapNotNull null
            val stop = s.events.firstOrNull { it.at >= ring.at && it.type == "stop" } ?: return@mapNotNull null
            ((stop.at - ring.at) / 60_000L).toInt()
        }
        val nights = recent.filter { s -> s.events.any { it.type == "alert" } }
            .map { Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSet()
        return NightStats(
            sessions = recent.count { s -> s.events.any { it.type in setOf("armed", "scheduled") } },
            nightsWithAlert = nights.size,
            rings = events.count { it.type == "ring" },
            snoozes = events.count { it.type == "snooze" },
            shifted = events.count { it.type == "shift" || it.type == "skip" },
            ringToStopMin = if (delays.isEmpty()) null else delays.average().toInt(),
        )
    }

    fun place(raw: List<LongRange>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): PlaceStats? {
        val alerts = NightRules.merge(raw.map { it.first..minOf(it.last, now) })
        if (alerts.isEmpty()) return null
        val minutes = alerts.map { ((it.last - it.first) / 60_000L).toInt() }
        val night = alerts.filter { Instant.ofEpochMilli(it.first).atZone(zone).hour in 0..5 }
        val endHours = night.map { Instant.ofEpochMilli(it.last).atZone(zone).hour }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Джерело віддає лише останні тривоги: дні до найдавнішої з них невідомі, а не «без тривог».
        val firstDay = Instant.ofEpochMilli(alerts.first().first).atZone(zone).toLocalDate()
        val perDay = (DAYS - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            if (day.isBefore(firstDay)) -1 else minutesOn(alerts, day, zone)
        }
        return PlaceStats(
            count = alerts.size,
            count7d = alerts.count { now - it.first <= 7 * 24 * 60 * 60_000L },
            avgMin = minutes.average().toInt(),
            longestMin = minutes.max(),
            nightShare = night.size * 100 / alerts.size,
            nightEndHour = endHours.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            perDay = perDay,
            since = alerts.first().first,
        )
    }

    private fun minutesOn(alerts: List<LongRange>, day: LocalDate, zone: ZoneId): Int {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (alerts.sumOf { maxOf(0L, minOf(it.last, end) - maxOf(it.first, start)) } / 60_000L).toInt()
    }
}
