package ua.vidbiy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Системний будильник для режиму «за розкладом». */
object AlarmScheduler {
    const val ACTION_FIRE = "ua.vidbiy.SCHEDULED_ALARM"
    const val WEEKDAYS = 0b0011111
    const val EVERY_DAY = 0b1111111
    const val WEEKEND = 0b1100000

    private val shortDays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun nextTrigger(prefs: Prefs, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!prefs.scheduleEnabled) return null
        val time = LocalTime.of(prefs.scheduleMinutes / 60, prefs.scheduleMinutes % 60)
        val days = prefs.scheduleDays
        for (i in 0..7L) {
            val date = now.toLocalDate().plusDays(i)
            val at = date.atTime(time).atZone(now.zone)
            if (!at.isAfter(now)) continue
            if (days == 0 || days and (1 shl (date.dayOfWeek.value - 1)) != 0) return at
        }
        return null
    }

    fun schedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = nextTrigger(prefs)
        if (next == null) {
            am.cancel(fire)
            return
        }
        val at = next.toInstant().toEpochMilli()
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
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun describeDays(days: Int): String = when (days) {
        0 -> "Один раз"
        EVERY_DAY -> "Щодня"
        WEEKDAYS -> "Будні"
        WEEKEND -> "Вихідні"
        else -> shortDays.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }

    /** «сьогодні о 07:30», «завтра о 07:30», «у пн о 07:30». */
    fun describeNext(prefs: Prefs, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val next = nextTrigger(prefs, now) ?: return null
        val time = timeFormat.format(next)
        return when (ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())) {
            0L -> "сьогодні о $time"
            1L -> "завтра о $time"
            else -> "${inDay(next.dayOfWeek)} о $time"
        }
    }

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

/** Спрацював будильник за розкладом: ставимо наступний і запускаємо перевірку тривоги. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val prefs = Prefs(context)
        if (!prefs.scheduleEnabled) return
        if (prefs.scheduleDays == 0) prefs.scheduleEnabled = false
        AlarmScheduler.schedule(context)
        WatchService.startScheduled(context, AlarmScheduler.formatMinutes(prefs.scheduleMinutes))
    }
}

/** Системні будильники зникають після перезавантаження, а зміна часу збиває розклад. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.schedule(context)
    }
}
