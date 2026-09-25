package ua.vidbiy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class AlertStatus { ACTIVE, PARTIAL, NONE }

enum class Source(val title: String) {
    SIREN("siren.pp.ua"),
    UBILLING("ubilling.net.ua"),
    ALERTS_IN_UA("alerts.in.ua"),
}

sealed interface ApiResult {
    data class Ok(val status: AlertStatus, val source: Source) : ApiResult
    data class Error(val message: String) : ApiResult
}

object AlertsApi {
    private const val SIREN = "https://siren.pp.ua/api/v3"

    /**
     * Запитує спершу [primary], а в разі збою — решту джерел. alerts.in.ua використовується лише з ключем.
     * Блокуючий виклик — запускати не з головного потоку.
     */
    fun fetch(primary: Source, region: Region, token: String): ApiResult {
        val order = listOf(primary) + Source.entries.filter { it != primary }
        var firstError: ApiResult.Error? = null
        for (source in order) {
            if (source == Source.ALERTS_IN_UA && token.isBlank()) continue
            when (val r = fetchFrom(source, region, token)) {
                is ApiResult.Ok -> return r
                is ApiResult.Error -> if (firstError == null) firstError = r
            }
        }
        return firstError ?: ApiResult.Error("Немає доступних джерел даних для регіону")
    }

    fun fetchFrom(source: Source, region: Region, token: String): ApiResult =
        when (source) {
            Source.SIREN -> region.sirenId?.let { id ->
                sirenDescendants(id)?.let { children ->
                    get(source, "$SIREN/alerts") { parseSiren(it, id, children) }
                } ?: ApiResult.Error("${source.title}: немає зв'язку")
            }
            Source.UBILLING -> region.ubillingName?.let { name ->
                get(source, "https://ubilling.net.ua/aerialalerts/") { parseUbilling(it, name) }
            }
            Source.ALERTS_IN_UA ->
                if (token.isBlank()) {
                    ApiResult.Error("${source.title}: не вказано ключ")
                } else {
                    get(source, "https://api.alerts.in.ua/v1/iot/active_air_raid_alerts/${region.uid}.json", token) {
                        parseAlertsInUa(it)
                    }
                }
        } ?: ApiResult.Error("${source.title}: регіон не підтримується")

    private fun get(source: Source, url: String, token: String? = null, parse: (String) -> AlertStatus?): ApiResult {
        return try {
            val conn = open(url, token)
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val status = try {
                            parse(body)
                        } catch (_: JSONException) {
                            null
                        }
                        status?.let { ApiResult.Ok(it, source) }
                            ?: ApiResult.Error("${source.title}: незрозуміла відповідь")
                    }
                    401, 403 -> ApiResult.Error("${source.title}: ключ недійсний")
                    429 -> ApiResult.Error("${source.title}: забагато запитів")
                    else -> ApiResult.Error("${source.title}: помилка сервера ($code)")
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            ApiResult.Error("${source.title}: немає зв'язку")
        }
    }

    private fun open(url: String, token: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Vidbiy/${BuildConfig.VERSION_NAME} (Android)")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    // Тривоги в областях оголошують здебільшого по районах і громадах, тому потрібне дерево регіонів.
    // Воно майже не змінюється — завантажується один раз за запуск.
    private var sirenTree: Map<String, Set<String>>? = null

    @Synchronized
    private fun sirenDescendants(id: String): Set<String>? {
        sirenTree?.let { return it[id].orEmpty() }
        return try {
            val conn = open("$SIREN/regions")
            try {
                if (conn.responseCode != 200) return null
                val states = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("states")
                val tree = HashMap<String, Set<String>>()
                for (i in 0 until states.length()) {
                    val state = states.getJSONObject(i)
                    val ids = HashSet<String>()
                    collectChildIds(state, ids)
                    tree[state.getString("regionId")] = ids
                }
                sirenTree = tree
                tree[id].orEmpty()
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun collectChildIds(node: JSONObject, into: MutableSet<String>) {
        val children = node.optJSONArray("regionChildIds") ?: return
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            into += child.getString("regionId")
            collectChildIds(child, into)
        }
    }

    // Лише регіони з активними тривогами: [{"regionId":"31", ..., "activeAlerts":[{"type":"AIR", ...}]}]
    private fun parseSiren(body: String, id: String, children: Set<String>): AlertStatus {
        val arr = JSONArray(body)
        var partial = false
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if ((item.optJSONArray("activeAlerts")?.length() ?: 0) == 0) continue
            val regionId = item.getString("regionId")
            if (regionId == id) return AlertStatus.ACTIVE
            if (regionId in children) partial = true
        }
        return if (partial) AlertStatus.PARTIAL else AlertStatus.NONE
    }

    // {"states":{"м. Київ":{"alertnow":false, ...}, ...}}
    private fun parseUbilling(body: String, name: String): AlertStatus? {
        val state = JSONObject(body).optJSONObject("states")?.optJSONObject(name) ?: return null
        if (!state.has("alertnow")) return null
        return if (state.getBoolean("alertnow")) AlertStatus.ACTIVE else AlertStatus.NONE
    }

    // "A" — тривога в усьому регіоні, "P" — у частині громад, "N" — немає
    private fun parseAlertsInUa(body: String): AlertStatus? =
        when (body.trim().trim('"')) {
            "A" -> AlertStatus.ACTIVE
            "P" -> AlertStatus.PARTIAL
            "N" -> AlertStatus.NONE
            else -> null
        }
}
