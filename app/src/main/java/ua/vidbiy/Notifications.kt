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

    const val ID_WATCH = 1
    const val ID_ALARM = 2
    const val ID_INFO = 3

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

    fun alarm(context: Context, reason: String): Notification {
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
            .addAction(0, "Вимкнути", WatchService.pendingAction(context, WatchService.ACTION_STOP))
            .addAction(0, "Ще 5 хв", WatchService.pendingAction(context, WatchService.ACTION_SNOOZE))
            .build()
    }

    fun info(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Гей-карта — демо")
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
