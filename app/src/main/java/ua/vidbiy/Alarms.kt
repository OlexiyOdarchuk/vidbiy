package ua.vidbiy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Будильник на час. [days] — дні тижня бітами (біт 0 — понеділок … біт 6 — неділя), 0 — один раз.
 * [place] — де перевіряти тривогу; null — основне місце з налаштувань.
 */
data class Alarm(
    val id: Int,
    val enabled: Boolean = true,
    val minutes: Int = 7 * 60 + 30,
    val days: Int = AlarmScheduler.WEEKDAYS,
    val nightRule: Boolean = false,
    val place: Region? = null,
)

enum class RuleAction { AT, LATER, SKIP }

/**
 * Правило нічної тривоги: якщо у вікні [from]–[to] тривога тривала щонайменше [minDuration] хв
 * (0 — будь-яка), будильник переноситься на [at], на [later] хв пізніше або не дзвонить.
 */
data class NightRule(
    val id: Int,
    val enabled: Boolean = true,
    val from: Int = 0,
    val to: Int = 6 * 60,
    val minDuration: Int = 0,
    val action: RuleAction = RuleAction.AT,
    val at: Int = 9 * 60,
    val later: Int = 120,
)

object AlarmsJson {
    fun alarms(text: String?): List<Alarm>? = parse(text) { o ->
        Alarm(
            id = o.getInt("id"),
            enabled = o.optBoolean("enabled", true),
            minutes = o.optInt("minutes", 7 * 60 + 30),
            days = o.optInt("days", AlarmScheduler.WEEKDAYS),
            nightRule = o.optBoolean("nightRule", false),
            place = o.optJSONObject("place")?.let(::region),
        )
    }

    fun write(alarms: List<Alarm>): String = JSONArray(alarms.map { a ->
        JSONObject()
            .put("id", a.id)
            .put("enabled", a.enabled)
            .put("minutes", a.minutes)
            .put("days", a.days)
            .put("nightRule", a.nightRule)
            .put("place", a.place?.let(::region))
    }).toString()

    fun rules(text: String?): List<NightRule>? = parse(text) { o ->
        NightRule(
            id = o.getInt("id"),
            enabled = o.optBoolean("enabled", true),
            from = o.optInt("from", 0),
            to = o.optInt("to", 6 * 60),
            minDuration = o.optInt("minDuration", 0),
            action = RuleAction.entries.firstOrNull { it.name == o.optString("action") } ?: RuleAction.AT,
            at = o.optInt("at", 9 * 60),
            later = o.optInt("later", 120),
        )
    }

    fun writeRules(rules: List<NightRule>): String = JSONArray(rules.map { r ->
        JSONObject()
            .put("id", r.id)
            .put("enabled", r.enabled)
            .put("from", r.from)
            .put("to", r.to)
            .put("minDuration", r.minDuration)
            .put("action", r.action.name)
            .put("at", r.at)
            .put("later", r.later)
    }).toString()

    fun region(r: Region): JSONObject = JSONObject()
        .put("uid", r.uid)
        .put("name", r.name)
        .put("sirenId", r.sirenId)
        .put("ubillingName", r.ubillingName)
        .put("detail", r.detail)

    fun region(o: JSONObject): Region = Region(
        uid = if (o.isNull("uid")) null else o.getInt("uid"),
        name = o.getString("name"),
        sirenId = o.optString("sirenId").takeIf { !o.isNull("sirenId") && it.isNotEmpty() },
        ubillingName = o.optString("ubillingName").takeIf { !o.isNull("ubillingName") && it.isNotEmpty() },
        detail = o.optString("detail").takeIf { !o.isNull("detail") && it.isNotEmpty() },
    )

    private fun <T> parse(text: String?, item: (JSONObject) -> T): List<T>? {
        if (text.isNullOrEmpty()) return null
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { item(arr.getJSONObject(it)) }
        } catch (_: JSONException) {
            null
        }
    }
}
