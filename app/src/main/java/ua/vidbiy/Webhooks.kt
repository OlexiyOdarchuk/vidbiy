package ua.vidbiy

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class HookEvent(val id: String, val title: String) {
    ARMED("armed", "Очікування увімкнено"),
    ALERT("alert", "Почалася тривога"),
    CLEAR("clear", "Відбій тривоги"),
    RING("ring", "Будильник задзвонив"),
    STOP("stop", "Будильник вимкнено"),
    TEST("test", "Перевірка"),
}

/** Запит на задану адресу, коли стається подія: для розумного дому (Home Assistant тощо) чи власного сервера. */
data class Webhook(
    val id: Int,
    val enabled: Boolean = true,
    val name: String = "",
    val url: String = "",
    val method: String = "POST",
    val events: Set<String> = setOf(HookEvent.CLEAR.id, HookEvent.RING.id),
    /** «Ключ: значення» по рядку. */
    val headers: String = "",
    /** Порожнє — стандартний JSON для POST/PUT. */
    val body: String = "",
)

object Webhooks {
    val methods = listOf("GET", "POST", "PUT")
    const val DEFAULT_BODY = """{"event":"{event}","place":"{place}","time":"{timestamp}","reason":"{reason}"}"""
    const val PLACEHOLDERS = "{event}, {event_name}, {place}, {time}, {timestamp}, {reason}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** Надсилає подію всім увімкненим вебхукам, що на неї підписані. Не блокує й не впливає на будильник. */
    fun fire(context: Context, event: HookEvent, place: String, reason: String = "") {
        val app = context.applicationContext
        val prefs = Prefs(app)
        prefs.webhooks.filter { it.enabled && event.id in it.events && it.url.isNotBlank() }.forEach { hook ->
            scope.launch { deliver(prefs, hook, event, place, reason) }
        }
    }

    fun test(context: Context, hook: Webhook, place: String, onDone: (String) -> Unit) {
        val prefs = Prefs(context.applicationContext)
        scope.launch { onDone(deliver(prefs, hook, HookEvent.TEST, place, "Перевірка з програми «Відбій»")) }
    }

    private suspend fun deliver(prefs: Prefs, hook: Webhook, event: HookEvent, place: String, reason: String): String {
        val now = System.currentTimeMillis()
        val vars = mapOf(
            "{event}" to event.id,
            "{event_name}" to event.title,
            "{place}" to place,
            "{time}" to timeFormat.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())),
            "{timestamp}" to Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString(),
            "{reason}" to reason,
        )
        var result = ""
        for (attempt in 0..1) {
            result = send(hook, vars)
            if (result.startsWith("Надіслано")) break
            delay(5_000)
        }
        val at = vars.getValue("{time}")
        prefs.setWebhookResult(hook.id, "$result ($at)")
        return result
    }

    fun substitute(text: String, vars: Map<String, String>, json: Boolean = false): String {
        var out = text
        for ((k, v) in vars) out = out.replace(k, if (json) JSONObject.quote(v).removeSurrounding("\"") else v)
        return out
    }

    private fun send(hook: Webhook, vars: Map<String, String>): String =
        try {
            val url = substitute(hook.url.trim(), vars.mapValues { java.net.URLEncoder.encode(it.value, "UTF-8") })
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = hook.method
                conn.setRequestProperty("User-Agent", "Vidbiy/${BuildConfig.VERSION_NAME} (Android)")
                val headers = parseHeaders(substitute(hook.headers, vars))
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (hook.method != "GET") {
                    val template = hook.body.ifBlank { DEFAULT_BODY }
                    val isJson = template.trimStart().startsWith("{") || template.trimStart().startsWith("[")
                    val body = substitute(template, vars, json = isJson)
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        conn.setRequestProperty("Content-Type", if (isJson) "application/json" else "text/plain; charset=utf-8")
                    }
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                if (code in 200..299) "Надіслано · $code" else "Помилка · відповідь $code"
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            "Помилка · немає зв'язку з адресою"
        } catch (_: IllegalArgumentException) {
            "Помилка · неправильна адреса"
        }

    fun parseHeaders(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }.toMap()

    fun parse(text: String?): List<Webhook> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val events = o.optJSONArray("events")
                Webhook(
                    id = o.getInt("id"),
                    enabled = o.optBoolean("enabled", true),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    method = o.optString("method", "POST"),
                    events = if (events == null) emptySet() else (0 until events.length()).map { events.getString(it) }.toSet(),
                    headers = o.optString("headers"),
                    body = o.optString("body"),
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun write(list: List<Webhook>): String = JSONArray(list.map { h ->
        JSONObject()
            .put("id", h.id)
            .put("enabled", h.enabled)
            .put("name", h.name)
            .put("url", h.url)
            .put("method", h.method)
            .put("events", JSONArray(h.events.toList()))
            .put("headers", h.headers)
            .put("body", h.body)
    }).toString()
}
