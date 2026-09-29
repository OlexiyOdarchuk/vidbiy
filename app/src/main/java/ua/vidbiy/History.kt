package ua.vidbiy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** [type] — для статистики: armed, scheduled, alert, clear, ring, snooze, stop, cutoff, shift, skip, offline. */
data class HistoryEvent(val at: Long, val text: String, val type: String? = null)

/** Сеанс — від увімкнення очікування чи спрацювання будильника на час до вимкнення. */
data class HistorySession(val startedAt: Long, val endedAt: Long?, val events: List<HistoryEvent>)

/** Журнал ночей: що відбувалося, коли й чому дзвонив будильник. Зберігає останні [MAX] сеансів. */
object History {
    private const val MAX = 30

    private val _sessions = MutableStateFlow<List<HistorySession>>(emptyList())
    val sessions: StateFlow<List<HistorySession>> = _sessions
    private var loaded = false

    /** Новий сеанс; якщо попередній не завершився (програму зупинила система), закриває його. */
    @Synchronized
    fun begin(context: Context, text: String, type: String? = null) {
        val now = System.currentTimeMillis()
        val list = load(context).map { if (it.endedAt == null) it.copy(endedAt = it.events.lastOrNull()?.at ?: now) else it }
        save(context, (listOf(HistorySession(now, null, listOf(HistoryEvent(now, text, type)))) + list).take(MAX))
    }

    /** Подія в поточному сеансі; якщо сеансу немає, починає його. */
    @Synchronized
    fun add(context: Context, text: String, type: String? = null) {
        val list = load(context)
        val current = list.firstOrNull()
        if (current == null || current.endedAt != null) {
            begin(context, text, type)
            return
        }
        val event = HistoryEvent(System.currentTimeMillis(), text, type)
        save(context, listOf(current.copy(events = current.events + event)) + list.drop(1))
    }

    @Synchronized
    fun end(context: Context, text: String? = null, type: String? = null) {
        val list = load(context)
        val current = list.firstOrNull()?.takeIf { it.endedAt == null } ?: return
        val now = System.currentTimeMillis()
        val events = if (text != null) current.events + HistoryEvent(now, text, type) else current.events
        save(context, listOf(current.copy(endedAt = now, events = events)) + list.drop(1))
    }

    /** Окремий завершений запис, наприклад коли правило перенесло будильник. */
    @Synchronized
    fun note(context: Context, text: String, type: String? = null) {
        val now = System.currentTimeMillis()
        save(context, (listOf(HistorySession(now, now, listOf(HistoryEvent(now, text, type)))) + load(context)).take(MAX))
    }

    @Synchronized
    fun clear(context: Context) = save(context, emptyList())

    @Synchronized
    fun refresh(context: Context) {
        _sessions.value = load(context)
    }

    private fun file(context: Context) = File(context.filesDir, "history.json")

    private fun load(context: Context): List<HistorySession> {
        if (loaded) return _sessions.value
        val f = file(context)
        _sessions.value = if (f.exists()) parse(f.readText()) else emptyList()
        loaded = true
        return _sessions.value
    }

    private fun save(context: Context, list: List<HistorySession>) {
        _sessions.value = list
        loaded = true
        val arr = JSONArray(list.map { s ->
            JSONObject()
                .put("start", s.startedAt)
                .put("end", s.endedAt ?: JSONObject.NULL)
                .put("events", JSONArray(s.events.map { JSONObject().put("at", it.at).put("text", it.text).put("type", it.type) }))
        })
        file(context).writeText(arr.toString())
    }

    private fun parse(text: String): List<HistorySession> =
        try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val events = o.getJSONArray("events")
                HistorySession(
                    startedAt = o.getLong("start"),
                    endedAt = if (o.isNull("end")) null else o.getLong("end"),
                    events = (0 until events.length()).map { j ->
                        events.getJSONObject(j).let {
                            HistoryEvent(it.getLong("at"), it.getString("text"), it.optString("type").takeIf { t -> t.isNotEmpty() })
                        }
                    },
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
}
