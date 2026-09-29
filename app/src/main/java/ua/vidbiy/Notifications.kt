package ua.vidbiy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    private const val CH_WATCH = "watch"
    private const val CH_ALARM = "alarm"
    private const val CH_INFO = "info"
    private const val CH_ALERT_START = "alert_start"
    private const val CH_WEAR = "wear_alarm"
    private const val CH_SUNRISE = "sunrise"

    const val ID_WATCH = 1
    const val ID_ALARM = 2
    const val ID_INFO = 3
    const val ID_ALERT_START = 4
    const val ID_WEAR = 5
    const val ID_SUNRISE = 6

    private val quietBuzz = longArrayOf(0, 300, 200, 300)
    private val wearBuzz = longArrayOf(0, 800, 600, 800, 600, 800)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Очікування відбою", NotificationManager.IMPORTANCE_LOW)
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Повідомлення", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT_START, "Початок тривоги", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = quietBuzz
            }
        )
        // Окремий канал з вібрацією, бо сповіщення будильника беззвучне: звук і вібрацію дає сама програма,
        // а годинник вібрує лише за налаштуваннями каналу.
        nm.createNotificationChannel(
            NotificationChannel(CH_WEAR, "Будильник на годиннику", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = wearBuzz
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUNRISE, "Світанок перед будильником", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun watch(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Будильник після відбою")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .addAction(0, "Скасувати", WatchService.pendingAction(context, WatchService.ACTION_STOP))
            .build()

    /** Якщо для вимкнення треба виконати завдання, «Вимкнути» відкриває екран сигналу. */
    private fun dismissAction(context: Context, withTask: Boolean): PendingIntent =
        if (withTask) activityIntent(context, AlarmActivity::class.java) else WatchService.pendingAction(context, WatchService.ACTION_STOP)

    fun alarm(context: Context, reason: String, localOnly: Boolean = false, withTask: Boolean = false): Notification {
        val fullScreen = activityIntent(context, AlarmActivity::class.java)
        return NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Прокидайтеся!")
            .setContentText(reason)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setLocalOnly(localOnly)
            .addAction(0, "Вимкнути", dismissAction(context, withTask))
            .addAction(0, "Ще 5 хв", WatchService.pendingAction(context, WatchService.ACTION_SNOOZE))
            .build()
    }

    /** Копія сигналу для годинника: пересилається через програму-компаньйон і вібрує там. */
    fun wearAlarm(context: Context, reason: String, withTask: Boolean = false): Notification =
        NotificationCompat.Builder(context, CH_WEAR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Прокидайтеся!")
            .setContentText(reason)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(wearBuzz)
            .setOngoing(true)
            .addAction(0, "Вимкнути", dismissAction(context, withTask))
            .addAction(0, "Ще 5 хв", WatchService.pendingAction(context, WatchService.ACTION_SNOOZE))
            .build()

    fun alertStart(context: Context, place: String): Notification =
        NotificationCompat.Builder(context, CH_ALERT_START)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Тривога: $place")
            .setContentText("Будильник розбудить після відбою.")
            .setVibrate(quietBuzz)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .build()

    fun sunrise(context: Context, at: Long): Notification {
        val screen = PendingIntent.getActivity(
            context,
            SunriseActivity::class.java.name.hashCode(),
            Intent(context, SunriseActivity::class.java)
                .putExtra(SunriseActivity.EXTRA_AT, at)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_SUNRISE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Світанок")
            .setContentText("Будильник о ${AlarmScheduler.formatTime(at)}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setAutoCancel(true)
            .build()
    }

    fun info(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Будильник після відбою")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, MainActivity::class.java))
            .build()

    private fun activityIntent(context: Context, cls: Class<*>): PendingIntent =
        PendingIntent.getActivity(
            context,
            cls.name.hashCode(),
            Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
