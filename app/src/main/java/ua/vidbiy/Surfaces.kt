package ua.vidbiy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Плитка в «Швидких налаштуваннях» і віджет стану: показують стан і вмикають чи вимикають очікування. */
object Surfaces {
    /** [alarmOn] — нічого не триває, але будильник на час увімкнено: дотик не вмикає очікування відбою одразу. */
    data class Status(val active: Boolean, val title: String, val subtitle: String, val alarmOn: Boolean = false)

    fun status(context: Context): Status {
        val state = WatchRepo.state.value
        val prefs = Prefs(context)
        return when (state.phase) {
            Phase.IDLE -> {
                val next = AlarmScheduler.next(prefs)?.first
                if (next != null) {
                    Status(false, "Будильник о ${AlarmScheduler.formatTime(next)}", "Увімкнено, спрацює сам", alarmOn = true)
                } else {
                    Status(false, "Вимкнено", "Торкніться, щоб чекати відбою")
                }
            }
            Phase.WAITING_ALERT -> Status(true, "Чекаю на тривогу", "Торкніться, щоб скасувати")
            Phase.ALERT -> Status(true, "Триває тривога", "Розбудить після відбою")
            Phase.CLEARING -> Status(true, "Відбій", "Перевіряю, чи утримається")
            Phase.RINGING -> Status(true, "Прокидайтеся!", "Торкніться, щоб вимкнути")
            Phase.SNOOZED -> Status(true, "Відкладено", "Торкніться, щоб вимкнути")
        }
    }

    fun refresh(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, widgetViews(context))
        Widgets.render(context)
        try {
            TileService.requestListeningState(context, ComponentName(context, WatchTile::class.java))
        } catch (_: Exception) {
            // Плитку не додано — оновлювати нічого.
        }
    }

    fun widgetViews(context: Context): RemoteViews {
        val s = status(context)
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteViews(context.packageName, R.layout.widget_status).apply {
            setTextViewText(R.id.widget_title, s.title)
            setTextViewText(R.id.widget_subtitle, s.subtitle)
            setImageViewResource(R.id.widget_icon, if (s.active) R.drawable.ic_widget_active else R.drawable.ic_widget_idle)
            setOnClickPendingIntent(R.id.widget_root, if (s.alarmOn) open else WatchService.togglePending(context))
            setOnClickPendingIntent(R.id.widget_open, open)
        }
    }
}

class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, Surfaces.widgetViews(context))
    }
}

class WatchTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val s = Surfaces.status(this)
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Відбій"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = s.title
        tile.updateTile()
    }

    override fun onClick() {
        val s = Surfaces.status(this)
        if (s.active) {
            if (WatchRepo.state.value.phase == Phase.RINGING && Prefs(this).dismissTask != DismissTask.NONE) {
                open(Intent(this, AlarmActivity::class.java))
            } else {
                WatchService.send(this, WatchService.ACTION_STOP)
            }
            return
        }
        val arm = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARM, true)
        if (s.alarmOn) {
            // Будильник на час уже ввімкнено — програма спершу перепитає, чи будити після найближчого відбою.
            open(arm)
            return
        }
        try {
            WatchService.arm(this)
        } catch (_: IllegalStateException) {
            // Система не дала запустити сервіс із фону — вмикаємо через програму.
            open(arm)
        }
    }

    private fun open(target: Intent) {
        val intent = target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
