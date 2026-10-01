package ua.vidbiy

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Що віджети знають про тривогу в основному місці. [place] — для якого місця ці дані,
 * [status] null — ще не знаємо, [since] — відколи триває тривога (0 — невідомо),
 * [lastStart]–[lastEnd] — остання тривога, що вже закінчилась, [intervals] — історія тривог
 * (null — не вдалося отримати), [failed] — остання спроба оновити не вдалася.
 */
data class WidgetAlert(
    val place: String = "",
    val status: AlertStatus? = null,
    val checkedAt: Long = 0L,
    val since: Long = 0L,
    val lastStart: Long = 0L,
    val lastEnd: Long = 0L,
    val intervals: List<LongRange>? = null,
    val failed: Boolean = false,
    val refreshingSince: Long = 0L,
)

/** Віджети «Тривога зараз», «Найближчий будильник», «Тривоги по днях» і «Панель». */
object Widgets {
    const val ACTION_REFRESH = "ua.vidbiy.WIDGET_REFRESH"

    const val JOB_PERIODIC = 1
    private const val JOB_ONCE = 2
    private const val PERIOD_MS = 15 * 60_000L
    /** Старіші дані не показуємо як поточний стан: тривога могла початися чи закінчитися. */
    private const val STALE_MS = 45 * 60_000L
    private const val REFRESH_AFTER_MS = 5 * 60_000L
    private const val REFRESHING_MS = 60_000L
    private const val MIN_CHART_DAYS = 7

    private const val TEXT = 0xFFF1F4FA.toInt()
    private const val DIM = 0xFFA7B4CC.toInt()
    private const val AMBER = 0xFFFFC247.toInt()
    private const val ON_AMBER = 0xFF2A1F00.toInt()
    private const val GREEN = 0xFF3DD68C.toInt()
    private const val RED = 0xFFFF6B6B.toInt()

    private val dayMonth = DateTimeFormatter.ofPattern("dd.MM")
    private val fullDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val withData = listOf(AlertWidget::class.java, ChartWidget::class.java, PanelWidget::class.java)
    private val all = withData + AlarmWidget::class.java

    private fun ids(context: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))

    private fun needsData(context: Context) = withData.any { ids(context, it).isNotEmpty() }

    private fun placeKey(place: Region) = place.sirenId ?: "uid${place.uid}"

    // ---------- Дані ----------

    private fun sp(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun read(context: Context): WidgetAlert {
        val text = sp(context).getString("alert", null) ?: return WidgetAlert()
        return try {
            val o = JSONObject(text)
            WidgetAlert(
                place = o.optString("place"),
                status = AlertStatus.entries.firstOrNull { it.name == o.optString("status") },
                checkedAt = o.optLong("checkedAt"),
                since = o.optLong("since"),
                lastStart = o.optLong("lastStart"),
                lastEnd = o.optLong("lastEnd"),
                intervals = o.optJSONArray("intervals")?.let { arr ->
                    (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getLong(0)..it.getLong(1) } }
                },
                failed = o.optBoolean("failed"),
                refreshingSince = o.optLong("refreshingSince"),
            )
        } catch (_: JSONException) {
            WidgetAlert()
        }
    }

    @Synchronized
    private fun edit(context: Context, change: (WidgetAlert) -> WidgetAlert): WidgetAlert {
        val a = change(read(context))
        val o = JSONObject()
            .put("place", a.place)
            .put("status", a.status?.name)
            .put("checkedAt", a.checkedAt)
            .put("since", a.since)
            .put("lastStart", a.lastStart)
            .put("lastEnd", a.lastEnd)
            .put("intervals", a.intervals?.let { list -> JSONArray(list.map { JSONArray().put(it.first).put(it.last) }) })
            .put("failed", a.failed)
            .put("refreshingSince", a.refreshingSince)
        sp(context).edit().putString("alert", o.toString()).commit()
        return a
    }

    /** Очікування щойно перевірило тривогу в основному місці — віджетам окремий запит не потрібен. */
    fun noteStatus(context: Context, place: Region, status: AlertStatus) {
        if (!needsData(context) || place != Prefs(context).region) return
        val key = placeKey(place)
        var changed = false
        edit(context) { old ->
            changed = old.place != key || old.status != status
            if (changed) {
                val base = if (old.place == key) old else WidgetAlert(place = key)
                base.copy(status = status, checkedAt = System.currentTimeMillis(), since = 0L, failed = false)
            } else {
                old.copy(checkedAt = System.currentTimeMillis(), failed = false)
            }
        }
        // Відколи триває тривога й коли була остання — з історії.
        if (changed) requestRefresh(context)
    }

    /** Раз на 15 хвилин: перемальовує віджети (дані могли застаріти, настав новий день) і просить свіжі дані. */
    fun tick(context: Context) {
        render(context)
        requestRefresh(context)
    }

    /** Запитує стан тривоги та історію й перемальовує віджети. Блокуючий виклик — не з головного потоку. */
    fun load(context: Context) {
        if (!needsData(context)) return
        RegionTreeRepo.init(context)
        val prefs = Prefs(context)
        val place = prefs.region
        val key = placeKey(place)
        val result = AlertsApi.fetch(prefs.source, place, prefs.token)
        val now = System.currentTimeMillis()
        val history = NightRules.history(place)?.let(NightRules::merge)
        edit(context) { old ->
            val base = if (old.place == key) old else WidgetAlert(place = key)
            val status = (result as? ApiResult.Ok)?.status
            val known = if (status != null) base.copy(status = status, checkedAt = now, failed = false) else base.copy(failed = true)
            val withHistory = if (history != null) {
                // Тривога, що триває, в історії закінчується «зараз».
                val ongoing = history.lastOrNull()?.takeIf { it.last >= now }
                val last = history.lastOrNull { it.last < now }
                known.copy(
                    intervals = history,
                    since = if (known.status == AlertStatus.ACTIVE) ongoing?.first ?: 0L else 0L,
                    lastStart = last?.first ?: 0L,
                    lastEnd = last?.last ?: 0L,
                )
            } else if (status != null && status != base.status) {
                known.copy(since = 0L)
            } else {
                known
            }
            withHistory.copy(refreshingSince = 0L)
        }
        render(context)
    }

    // ---------- Оновлення ----------

    private fun job(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, WidgetJob::class.java))

    /** Періодичне оновлення працює, лише поки на екрані є хоч один із цих віджетів. */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (all.none { ids(context, it).isNotEmpty() }) {
            scheduler.cancel(JOB_PERIODIC)
            scheduler.cancel(JOB_ONCE)
        } else if (scheduler.getPendingJob(JOB_PERIODIC) == null) {
            scheduler.schedule(job(context, JOB_PERIODIC).setPeriodic(PERIOD_MS).setPersisted(true).build())
        }
    }

    /** [manual] — оновлення попросили дотиком: показуємо «Оновлення…». */
    fun requestRefresh(context: Context, manual: Boolean = false) {
        if (!needsData(context)) return
        if (manual) {
            edit(context) { it.copy(refreshingSince = System.currentTimeMillis()) }
            render(context)
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Те саме завдання, поставлене вдруге, скасувало б завантаження, яке вже йде.
        if (scheduler.getPendingJob(JOB_ONCE) != null) return
        // Умова «є мережа» ще й дає завданню доступ до мережі, коли система обмежує програму у фоні.
        scheduler.schedule(job(context, JOB_ONCE).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }

    /** Оновлює дані, якщо вони для іншого місця або давні: програму відкрили чи додали віджет. */
    fun refreshIfStale(context: Context) {
        if (!needsData(context)) return
        val a = read(context)
        if (a.place != placeKey(Prefs(context).region) || System.currentTimeMillis() - a.checkedAt > REFRESH_AFTER_MS) {
            requestRefresh(context)
        }
    }

    // ---------- Вигляд ----------

    fun render(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val alertIds = ids(context, AlertWidget::class.java)
        val alarmIds = ids(context, AlarmWidget::class.java)
        val chartIds = ids(context, ChartWidget::class.java)
        val panelIds = ids(context, PanelWidget::class.java)
        if (alertIds.isEmpty() && alarmIds.isEmpty() && chartIds.isEmpty() && panelIds.isEmpty()) return

        val prefs = Prefs(context)
        val alert = read(context)
        if (alertIds.isNotEmpty()) mgr.updateAppWidget(alertIds, alertViews(context, prefs, alert))
        if (alarmIds.isNotEmpty()) mgr.updateAppWidget(alarmIds, alarmViews(context, prefs))
        if (panelIds.isNotEmpty()) mgr.updateAppWidget(panelIds, panelViews(context, prefs, alert))
        // Графік малюється під розмір кожного віджета окремо.
        chartIds.forEach { mgr.updateAppWidget(it, chartViews(context, prefs, alert, mgr.getAppWidgetOptions(it))) }
    }

    private class AlertText(val title: String, val detail: String, val footer: String, val color: Int, val active: Boolean)

    private fun alertText(prefs: Prefs, a: WidgetAlert, now: Long = System.currentTimeMillis()): AlertText {
        val mine = a.place == placeKey(prefs.region)
        val known = mine && a.status != null && a.checkedAt > 0
        val time = AlarmScheduler.formatTime(a.checkedAt)
        val footer = when {
            now - a.refreshingSince < REFRESHING_MS -> "Оновлення…"
            !known -> if (mine && a.failed) "Немає зв'язку" else "Оновлення…"
            a.failed -> "Немає зв'язку · дані на $time"
            else -> "Оновлено о $time"
        }
        if (!known) return AlertText("Немає даних", "", footer, DIM, false)
        if (now - a.checkedAt > STALE_MS) {
            return AlertText("Дані застаріли", "Останнє оновлення: ${day(a.checkedAt, now)} о $time", footer, DIM, false)
        }
        return when (a.status) {
            AlertStatus.ACTIVE -> AlertText("Тривога", sinceText(a.since, now), footer, RED, true)
            AlertStatus.PARTIAL -> AlertText("Тривога", "У частині регіону", footer, RED, true)
            else -> {
                val last = if (a.lastEnd > 0) {
                    "Остання: ${day(a.lastEnd, now)}, ${AlarmScheduler.formatTime(a.lastStart)}–${AlarmScheduler.formatTime(a.lastEnd)}"
                } else {
                    ""
                }
                AlertText("Тривоги немає", last, footer, GREEN, false)
            }
        }
    }

    /** «Триває з 03:12», «Триває з вчора, 23:10», «Триває з 04.04.2022». */
    private fun sinceText(since: Long, now: Long): String {
        if (since <= 0) return "Триває"
        val d = day(since, now)
        return when {
            d == "сьогодні" -> "Триває з ${AlarmScheduler.formatTime(since)}"
            now - since > 7 * 24 * 60 * 60_000L -> "Триває з $d"
            else -> "Триває з $d, ${AlarmScheduler.formatTime(since)}"
        }
    }

    /** «сьогодні», «вчора», «12.09», а для іншого року — «12.09.2025». */
    private fun day(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            date == today -> "сьогодні"
            date == today.minusDays(1) -> "вчора"
            date.year == today.year -> dayMonth.format(date)
            else -> fullDate.format(date)
        }
    }

    private fun activity(context: Context, code: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(context: Context) = activity(context, 3)

    private fun openScreen(context: Context, code: Int, screen: String) =
        activity(context, code) { putExtra(MainActivity.EXTRA_SCREEN, screen) }

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 8,
            Intent(context, AlertWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alertViews(context: Context, prefs: Prefs, alert: WidgetAlert): RemoteViews {
        val t = alertText(prefs, alert)
        return RemoteViews(context.packageName, R.layout.widget_alert).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (t.active) R.drawable.widget_bg_alert else R.drawable.widget_bg)
            setTextViewText(R.id.alert_place, prefs.region.name)
            setTextViewText(R.id.alert_title, t.title)
            setTextColor(R.id.alert_title, t.color)
            setTextViewText(R.id.alert_detail, t.detail)
            setViewVisibility(R.id.alert_detail, if (t.detail.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.alert_footer, t.footer)
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
            setOnClickPendingIntent(R.id.alert_refresh, refreshIntent(context))
        }
    }

    private class AlarmText(val time: String, val day: String, val extra: String)

    private fun alarmText(prefs: Prefs): AlarmText? {
        val (at, alarm) = AlarmScheduler.next(prefs) ?: return null
        val more = prefs.alarms.count { it.enabled } - 1
        val extra = if (prefs.shiftAt == at) {
            "Перенесено через нічну тривогу"
        } else {
            listOfNotNull(
                alarm?.let { AlarmScheduler.describeDays(it.days) },
                alarm?.place?.name,
                "ще $more".takeIf { more > 0 },
            ).joinToString(" · ")
        }
        val day = AlarmScheduler.describeAt(at).substringBefore(" о ").replaceFirstChar { it.uppercase() }
        return AlarmText(AlarmScheduler.formatTime(at), day, extra)
    }

    private fun alarmViews(context: Context, prefs: Prefs): RemoteViews {
        val t = alarmText(prefs)
        return RemoteViews(context.packageName, R.layout.widget_alarm).apply {
            setTextViewText(R.id.alarm_time, t?.time ?: "Вимкнено")
            setTextViewTextSize(R.id.alarm_time, TypedValue.COMPLEX_UNIT_SP, if (t != null) 36f else 22f)
            setTextColor(R.id.alarm_time, if (t != null) TEXT else DIM)
            setTextViewText(R.id.alarm_when, t?.day ?: "Жоден будильник на час не ввімкнено")
            setTextColor(R.id.alarm_when, if (t != null) TEXT else DIM)
            setTextViewText(R.id.alarm_extra, t?.extra.orEmpty())
            setViewVisibility(R.id.alarm_extra, if (t?.extra.isNullOrEmpty()) View.GONE else View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_root, openScreen(context, 6, MainActivity.SCREEN_SCHEDULE))
        }
    }

    private fun panelViews(context: Context, prefs: Prefs, alert: WidgetAlert): RemoteViews {
        val a = alertText(prefs, alert)
        val alarm = alarmText(prefs)
        val s = Surfaces.status(context)
        val action = when (WatchRepo.state.value.phase) {
            Phase.IDLE -> "Будити після найближчого відбою"
            Phase.WAITING_ALERT -> "Чекаю на тривогу · Скасувати"
            Phase.ALERT -> "Розбудить після відбою · Скасувати"
            Phase.CLEARING -> "Відбій, чекаю · Скасувати"
            Phase.RINGING -> "Прокидайтеся! · Вимкнути"
            Phase.SNOOZED -> "Відкладено · Вимкнути"
        }
        // Початок тривоги сьогодні вміщається в один рядок зі станом.
        val status = if (alert.status == AlertStatus.ACTIVE && a.detail.startsWith("Триває з") && a.detail.length <= 14) {
            "Тривога з ${a.detail.removePrefix("Триває з ")}"
        } else {
            a.title
        }
        return RemoteViews(context.packageName, R.layout.widget_panel).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (a.active) R.drawable.widget_bg_alert else R.drawable.widget_bg)
            setTextViewText(R.id.panel_place, prefs.region.name)
            setTextViewText(R.id.panel_status, status)
            setTextColor(R.id.panel_status, a.color)
            setTextViewText(R.id.panel_updated, a.footer)
            setTextViewText(R.id.panel_time, alarm?.time ?: "Вимкнено")
            setTextColor(R.id.panel_time, if (alarm != null) TEXT else DIM)
            setTextViewText(R.id.panel_when, alarm?.let { listOf(it.day, it.extra).filter(String::isNotEmpty).joinToString(" · ") } ?: "Не ввімкнено")
            setTextColor(R.id.panel_when, if (alarm != null) TEXT else DIM)
            // Поки нічого не триває, кнопка яскрава — це головна дія; далі вона показує стан.
            setInt(R.id.panel_action, "setBackgroundResource", if (s.active) R.drawable.widget_button_active else R.drawable.widget_button)
            setTextViewText(R.id.panel_action_text, action)
            setTextColor(R.id.panel_action_text, if (s.active) AMBER else ON_AMBER)
            setImageViewResource(R.id.panel_icon, if (s.active) R.drawable.ic_widget_active else R.drawable.ic_widget_idle)
            setInt(R.id.panel_icon, "setColorFilter", if (s.active) AMBER else ON_AMBER)
            setOnClickPendingIntent(R.id.panel_alert, refreshIntent(context))
            setOnClickPendingIntent(R.id.panel_alarm, openScreen(context, 6, MainActivity.SCREEN_SCHEDULE))
            // Будильник на час уже ввімкнено — програма спершу перепитає, чи будити після найближчого відбою.
            setOnClickPendingIntent(
                R.id.panel_action,
                if (s.alarmOn) activity(context, 5) { putExtra(MainActivity.EXTRA_ARM, true) } else WatchService.togglePending(context),
            )
        }
    }

    private fun chartViews(context: Context, prefs: Prefs, alert: WidgetAlert, options: Bundle): RemoteViews {
        val now = System.currentTimeMillis()
        val mine = alert.place == placeKey(prefs.region)
        val stats = alert.intervals?.takeIf { mine }?.let { Stats.place(it, now) }
        val empty = when {
            stats != null -> null
            now - alert.refreshingSince < REFRESHING_MS -> "Оновлення…"
            prefs.region.sirenId == null -> "Для цього місця історії тривог немає"
            mine && alert.intervals != null -> "Останнім часом тривог не було"
            mine && (alert.failed || alert.checkedAt > 0) -> "Не вдалося завантажити історію тривог"
            else -> "Оновлення…"
        }
        return RemoteViews(context.packageName, R.layout.widget_chart).apply {
            setTextViewText(R.id.chart_title, "Тривоги · ${prefs.region.name}")
            setViewVisibility(R.id.chart_empty, if (empty != null) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.chart_image, if (stats != null) View.VISIBLE else View.GONE)
            setTextViewText(R.id.chart_empty, empty.orEmpty())
            if (stats != null) {
                // Джерело віддає лише останні тривоги: якщо вони всі свіжі, за тиждень відомо не все.
                val week = stats.perDay.takeLast(7)
                val total = NightRules.formatDuration(week.sumOf { maxOf(it, 0) })
                setTextViewText(
                    R.id.chart_summary,
                    if (week.none { it < 0 }) "7 днів: $total" else "з ${day(stats.since, now)}: $total",
                )
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
                // Дні, про які джерело нічого не знає, не показуємо — лишаємо хіба що тиждень.
                val days = stats.perDay.takeLast(maxOf(stats.perDay.dropWhile { it < 0 }.size, MIN_CHART_DAYS))
                setImageViewBitmap(R.id.chart_image, chart(context, days, w - 28, h - 56, now))
            } else {
                setTextViewText(R.id.chart_summary, "")
            }
            setOnClickPendingIntent(R.id.widget_root, openScreen(context, 7, MainActivity.SCREEN_STATS))
        }
    }

    /** Стовпчики: хвилини тривоги за кожен із останніх днів; під ними — числа місяця. */
    private fun chart(context: Context, minutes: List<Int>, widthDp: Int, heightDp: Int, now: Long): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (widthDp.coerceIn(120, 800) * d).toInt()
        val h = (heightDp.coerceIn(40, 400) * d).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM
            textSize = 10 * d
            textAlign = Paint.Align.CENTER
        }
        val gap = 4 * d
        val barsH = h - 15 * d
        val barW = (w - gap * (minutes.size - 1)) / minutes.size
        val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(60)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        minutes.forEachIndexed { i, m ->
            val barH = when {
                m < 0 -> barsH
                m == 0 -> 2 * d
                else -> (barsH * m / max).coerceAtLeast(3 * d)
            }
            bar.color = when {
                m < 0 -> 0x0DF1F4FA
                m == 0 -> 0x33FFFFFF
                else -> AMBER
            }
            val x = i * (barW + gap)
            canvas.drawRoundRect(x, barsH - barH, x + barW, barsH, 3 * d, 3 * d, bar)
            if (i % 2 == (minutes.size - 1) % 2) {
                val date: LocalDate = today.minusDays((minutes.size - 1 - i).toLong())
                canvas.drawText("${date.dayOfMonth}", x + barW / 2, h - 3 * d, label)
            }
        }
        return bitmap
    }
}

abstract class DataWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.sync(context)
        Widgets.render(context)
        Widgets.refreshIfStale(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.render(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) = Widgets.sync(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_REFRESH) Widgets.requestRefresh(context, manual = true) else super.onReceive(context, intent)
    }
}

class AlertWidget : DataWidget()

class AlarmWidget : DataWidget()

class ChartWidget : DataWidget()

class PanelWidget : DataWidget()

/** Оновлення віджетів: раз на 15 хвилин і на вимогу; дані завантажує окреме завдання, якому потрібна мережа. */
class WidgetJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == Widgets.JOB_PERIODIC) {
            Widgets.tick(applicationContext)
            return false
        }
        thread {
            try {
                Widgets.load(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
