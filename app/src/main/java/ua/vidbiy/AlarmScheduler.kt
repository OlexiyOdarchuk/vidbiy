package ua.vidbiy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Системні будильники для будильників на час. */
object AlarmScheduler {
    const val ACTION_FIRE = "ua.vidbiy.SCHEDULED_ALARM"
    const val ACTION_SUNRISE = "ua.vidbiy.SUNRISE"
    const val WEEKDAYS = 0b0011111
    const val EVERY_DAY = 0b1111111
    const val WEEKEND = 0b1100000

    private val shortDays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun formatTime(millis: Long): String = timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun nextTrigger(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!alarm.enabled) return null
        val time = LocalTime.of(alarm.minutes / 60, alarm.minutes % 60)
        for (i in 0..7L) {
            val date = now.toLocalDate().plusDays(i)
            val at = date.atTime(time).atZone(now.zone)
            if (!at.isAfter(now)) continue
            if (alarm.days == 0 || alarm.days and (1 shl (date.dayOfWeek.value - 1)) != 0) return at
        }
        return null
    }

    /** Найближче спрацювання: будильник, перенесений правилом, або найраніший з увімкнених. */
    fun next(prefs: Prefs, now: ZonedDateTime = ZonedDateTime.now()): Pair<Long, Alarm?>? {
        val nowMs = now.toInstant().toEpochMilli()
        val shifted = prefs.shiftAt.takeIf { it > nowMs }?.let { at -> at to prefs.alarms.firstOrNull { it.id == prefs.shiftAlarmId } }
        val regular = prefs.alarms.mapNotNull { a -> nextTrigger(a, now)?.let { it.toInstant().toEpochMilli() to a } }
            .minByOrNull { it.first }
        return listOfNotNull(shifted, regular).minByOrNull { it.first }
    }

    fun schedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = broadcast(context, 0, ACTION_FIRE)
        val sunrise = broadcast(context, 2, ACTION_SUNRISE)
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        if (prefs.shiftAt in 1..nowMs) prefs.shiftAt = 0

        val regular = prefs.alarms.mapNotNull { a -> nextTrigger(a, now)?.let { it.toInstant().toEpochMilli() to a } }
        val regularAt = regular.minOfOrNull { it.first }
        val shiftAt = prefs.shiftAt.takeIf { it > nowMs }
        val at = listOfNotNull(regularAt, shiftAt).minOrNull()
        am.cancel(sunrise)
        if (at == null) {
            am.cancel(fire)
            prefs.pendingAt = 0
            prefs.pendingIds = emptyList()
            prefs.pendingShifted = false
            Surfaces.refresh(context)
            return
        }
        // Кілька будильників на ту саму хвилину дзвонять як один.
        prefs.pendingAt = at
        prefs.pendingShifted = at == shiftAt
        prefs.pendingIds = if (at == shiftAt) listOf(prefs.shiftAlarmId) else regular.filter { it.first == at }.map { it.second.id }

        val show = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            // setAlarmClock точний навіть у режимі сну, а система показує значок будильника.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (_: SecurityException) {
            // Без дозволу на точні будильники (Android 12, якщо його вимкнули) — хоча б приблизно.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }

        val sunriseAt = at - prefs.sunriseMinutes * 60_000L
        if (prefs.sunriseMinutes > 0 && sunriseAt > nowMs) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sunriseAt, sunrise)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sunriseAt, sunrise)
            }
        }
        Surfaces.refresh(context)
    }

    private fun broadcast(context: Context, code: Int, action: String) = PendingIntent.getBroadcast(
        context, code,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun describeDays(days: Int): String = when (days) {
        0 -> "Один раз"
        EVERY_DAY -> "Щодня"
        WEEKDAYS -> "Будні"
        WEEKEND -> "Вихідні"
        else -> shortDays.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }

    /** «сьогодні о 07:30», «завтра о 07:30», «у понеділок о 07:30». */
    fun describeAt(at: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        val next = Instant.ofEpochMilli(at).atZone(now.zone)
        val time = timeFormat.format(next)
        return when (ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())) {
            0L -> "сьогодні о $time"
            1L -> "завтра о $time"
            else -> "${inDay(next.dayOfWeek)} о $time"
        }
    }

    fun describeNext(prefs: Prefs, now: ZonedDateTime = ZonedDateTime.now()): String? =
        next(prefs, now)?.let { describeAt(it.first, now) }

    private fun inDay(day: DayOfWeek) = when (day) {
        DayOfWeek.MONDAY -> "у понеділок"
        DayOfWeek.TUESDAY -> "у вівторок"
        DayOfWeek.WEDNESDAY -> "у середу"
        DayOfWeek.THURSDAY -> "у четвер"
        DayOfWeek.FRIDAY -> "у п'ятницю"
        DayOfWeek.SATURDAY -> "у суботу"
        DayOfWeek.SUNDAY -> "у неділю"
    }
}

/** Спрацював системний будильник: ставимо наступний і запускаємо перевірку тривоги (або світанок). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val at = prefs.pendingAt
        val ids = prefs.pendingIds
        val shifted = prefs.pendingShifted
        if (at == 0L || ids.isEmpty()) return
        when (intent.action) {
            AlarmScheduler.ACTION_SUNRISE -> WatchService.sunrise(context, at, ids.first(), applyRules = !shifted)

            AlarmScheduler.ACTION_FIRE -> {
                val alarms = prefs.alarms
                val due = alarms.filter { it.id in ids }
                if (shifted) {
                    prefs.shiftAt = 0
                } else {
                    // Одноразовий будильник вимикається, щойно спрацював.
                    prefs.alarms = alarms.map { a -> if (a.id in ids && a.days == 0) a.copy(enabled = false) else a }
                }
                AlarmScheduler.schedule(context)
                // Якщо будильник видалили, поки він був перенесений, — дзвонимо як за основним місцем.
                val alarm = due.firstOrNull { it.nightRule } ?: due.firstOrNull() ?: Alarm(id = ids.first())
                WatchService.startScheduled(context, alarm, at, applyRules = !shifted && alarm.nightRule)
            }
        }
    }
}

/** Системні будильники зникають після перезавантаження, а зміна часу збиває розклад. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.schedule(context)
    }
}
