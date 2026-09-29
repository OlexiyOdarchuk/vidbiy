package ua.vidbiy

import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

sealed interface RuleOutcome {
    data object None : RuleOutcome
    /** Будити о [at] замість звичайного часу; уночі тривога тривала [alertMs]. */
    data class Shift(val at: Long, val alertMs: Long) : RuleOutcome
    data class Skip(val alertMs: Long) : RuleOutcome
}

/** Правила нічної тривоги: переносять будильник на час, якщо вночі була тривога. */
object NightRules {
    private const val HISTORY = "https://siren.pp.ua/api/v3/alerts/regionHistory?regionId="
    private const val DAY_MIN = 24 * 60

    val durations = listOf(0, 15, 30, 60, 120, 180, 240)
    val laterOptions = listOf(30, 60, 90, 120, 180)

    fun defaultRule(id: Int) = NightRule(id, from = 0, to = 6 * 60, minDuration = 0, action = RuleAction.AT, at = 9 * 60)

    /** Шаблони для «Додати правило». */
    fun templates(id: Int) = listOf(
        defaultRule(id),
        NightRule(id, from = 0, to = 6 * 60, minDuration = 0, action = RuleAction.LATER, later = 120),
        NightRule(id, from = 22 * 60, to = 6 * 60, minDuration = 180, action = RuleAction.SKIP),
    )

    /**
     * [baseAt] — коли будильник мав спрацювати, [alerts] — інтервали тривог (кінець — зараз, якщо триває).
     * «Не будити» перемагає; з кількох переносів — найпізніший.
     */
    fun evaluate(rules: List<NightRule>, baseAt: Long, alerts: List<LongRange>, zone: ZoneId = ZoneId.systemDefault()): RuleOutcome {
        val merged = merge(alerts)
        val base = Instant.ofEpochMilli(baseAt).atZone(zone)
        var shift: RuleOutcome.Shift? = null
        for (rule in rules.filter { it.enabled }) {
            var start = base.toLocalDate().atTime(rule.from / 60, rule.from % 60).atZone(zone)
            if (!start.isBefore(base)) start = start.minusDays(1)
            val len = ((rule.to - rule.from) % DAY_MIN + DAY_MIN) % DAY_MIN
            val startMs = start.toInstant().toEpochMilli()
            val endMs = minOf(start.plusMinutes((if (len == 0) DAY_MIN else len).toLong()).toInstant().toEpochMilli(), baseAt)
            val dur = merged.sumOf { maxOf(0L, minOf(it.last, endMs) - maxOf(it.first, startMs)) }
            if (dur <= 0 || dur < rule.minDuration * 60_000L) continue
            when (rule.action) {
                RuleAction.SKIP -> return RuleOutcome.Skip(dur)
                RuleAction.AT -> {
                    val at = base.toLocalDate().atTime(rule.at / 60, rule.at % 60).atZone(zone).toInstant().toEpochMilli()
                    if (at > baseAt && (shift == null || at > shift.at)) shift = RuleOutcome.Shift(at, dur)
                }
                RuleAction.LATER -> {
                    val at = baseAt + rule.later * 60_000L
                    if (shift == null || at > shift.at) shift = RuleOutcome.Shift(at, dur)
                }
            }
        }
        return shift ?: RuleOutcome.None
    }

    private fun merge(intervals: List<LongRange>): List<LongRange> {
        val out = ArrayList<LongRange>()
        for (r in intervals.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last) {
                out[out.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                out += r
            }
        }
        return out
    }

    /**
     * Тривоги за останні дні для місця та всього, що його охоплює (район, область).
     * null — історію не вдалося отримати; тоді правила не застосовуються.
     */
    fun history(place: Region): List<LongRange>? {
        val id = place.sirenId ?: return null
        val tree = RegionTreeRepo.get()
        val ids = listOf(id) + (tree?.ancestors(id) ?: emptyList())
        val now = System.currentTimeMillis()
        val result = ArrayList<LongRange>()
        for (regionId in ids) {
            val body = fetch(HISTORY + regionId) ?: return null
            try {
                val regions = JSONArray(body)
                for (i in 0 until regions.length()) {
                    val alarms = regions.getJSONObject(i).optJSONArray("alarms") ?: continue
                    for (j in 0 until alarms.length()) {
                        val a = alarms.getJSONObject(j)
                        val start = Instant.parse(a.getString("startDate")).toEpochMilli()
                        val end = if (a.isNull("endDate") || a.optBoolean("isContinue")) {
                            now
                        } else {
                            Instant.parse(a.getString("endDate")).toEpochMilli()
                        }
                        if (end > start) result += start..end
                    }
                }
            } catch (_: JSONException) {
                return null
            } catch (_: DateTimeParseException) {
                return null
            }
        }
        return result
    }

    private fun fetch(url: String): String? =
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "Vidbiy/${BuildConfig.VERSION_NAME} (Android)")
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }

    /** «Тривога понад 2 год з 22:00 до 06:00 → о 09:00». */
    fun describe(rule: NightRule): String {
        val window = "з ${Prefs.formatMinutes(rule.from)} до ${Prefs.formatMinutes(rule.to)}"
        val cond = if (rule.minDuration > 0) "Тривога понад ${formatDuration(rule.minDuration)} $window" else "Тривога $window"
        return "$cond → ${describeAction(rule)}"
    }

    fun describeAction(rule: NightRule): String = when (rule.action) {
        RuleAction.AT -> "о ${Prefs.formatMinutes(rule.at)}"
        RuleAction.LATER -> "на ${formatDuration(rule.later)} пізніше"
        RuleAction.SKIP -> "не будити"
    }

    /** 90 → «1 год 30 хв», 120 → «2 год», 40 → «40 хв». */
    fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m хв"
            m == 0 -> "$h год"
            else -> "$h год $m хв"
        }
    }

    fun formatDurationMs(ms: Long) = formatDuration(maxOf(1, ((ms + 30_000) / 60_000).toInt()))
}
