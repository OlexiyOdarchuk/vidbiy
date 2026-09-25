package ua.vidbiy

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private lateinit var player: AlarmPlayer
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        player = AlarmPlayer(this)
        nm = getSystemService(NotificationManager::class.java)
        Notifications.createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCH -> {
                goForeground("Перевірка стану тривоги…")
                startWatching()
            }

            ACTION_TEST -> {
                goForeground("Перевірка звуку")
                job?.cancel()
                ring("Перевірка будильника")
            }

            ACTION_SNOOZE -> snooze()
            ACTION_STOP -> finish(null)
            null -> if (prefs.armed) {
                goForeground("Перевірка стану тривоги…")
                startWatching()
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        player.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startWatching() {
        job?.cancel()
        job = scope.launch {
            val region = Regions.byUid(prefs.regionUid)
            var lastOk = System.currentTimeMillis()
            var phase = if (prefs.sawAlert) Phase.ALERT else Phase.WAITING_ALERT

            while (isActive) {
                val cutoff = prefs.cutoffAt
                if (cutoff > 0 && System.currentTimeMillis() >= cutoff) {
                    finish("Настав час ${formatTime(cutoff)} — будильник вимкнено без сигналу")
                    return@launch
                }

                val result = withContext(Dispatchers.IO) { AlertsApi.fetch(prefs.source, region, prefs.token) }
                when (result) {
                    is ApiResult.Ok -> {
                        lastOk = System.currentTimeMillis()
                        val via = result.source.title
                        if (result.status != AlertStatus.NONE) {
                            prefs.sawAlert = true
                            phase = Phase.ALERT
                            val text = if (result.status == AlertStatus.PARTIAL) {
                                "Тривога в частині регіону. Будильник пролунає після відбою в усьому регіоні."
                            } else {
                                "Будильник пролунає після відбою."
                            }
                            update(phase, text, via)
                        } else if (prefs.sawAlert) {
                            ring("Відбій тривоги: ${region.name}")
                            return@launch
                        } else {
                            phase = Phase.WAITING_ALERT
                            update(phase, "Зараз тривоги немає. Будильник пролунає після відбою наступної тривоги.", via)
                        }
                    }

                    is ApiResult.Error -> {
                        val offline = System.currentTimeMillis() - lastOk
                        if (prefs.alarmOnNoConnection && offline >= NO_CONNECTION_MS) {
                            ring("Понад ${NO_CONNECTION_MS / 60_000} хв немає зв'язку з жодним джерелом даних про тривоги")
                            return@launch
                        }
                        update(phase, "${result.message}. Повторна спроба…")
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun ring(reason: String) {
        acquireWakeLock()
        WatchRepo.set(WatchState(Phase.RINGING, reason, reason))
        nm.notify(Notifications.ID_ALARM, Notifications.alarm(this, reason))
        player.start(scope)
        startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun snooze() {
        val state = WatchRepo.state.value
        if (state.phase != Phase.RINGING) {
            if (job == null) stopSelf()
            return
        }
        player.stop()
        nm.cancel(Notifications.ID_ALARM)
        val at = System.currentTimeMillis() + SNOOZE_MS
        val text = "Повторний сигнал о ${formatTime(at)}"
        WatchRepo.set(WatchState(Phase.SNOOZED, text, state.reason))
        nm.notify(Notifications.ID_WATCH, Notifications.watch(this, text))
        job?.cancel()
        job = scope.launch {
            delay(SNOOZE_MS)
            ring(state.reason)
        }
    }

    private fun finish(message: String?) {
        job?.cancel()
        player.stop()
        nm.cancel(Notifications.ID_ALARM)
        prefs.armed = false
        prefs.sawAlert = false
        WatchRepo.set(WatchState(Phase.IDLE, message ?: ""))
        if (message != null) nm.notify(Notifications.ID_INFO, Notifications.info(this, message))
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(phase: Phase, text: String, source: String? = null) {
        val prev = WatchRepo.state.value
        WatchRepo.set(
            WatchState(
                phase = phase,
                text = text,
                source = source ?: prev.source,
                checkedAt = if (source != null) System.currentTimeMillis() else prev.checkedAt,
            )
        )
        nm.notify(Notifications.ID_WATCH, Notifications.watch(this, text))
    }

    private fun goForeground(text: String) {
        acquireWakeLock()
        ServiceCompat.startForeground(
            this,
            Notifications.ID_WATCH,
            Notifications.watch(this, text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidbiy:watch")
            .apply { acquire(WAKE_LOCK_MAX_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_WATCH = "ua.vidbiy.WATCH"
        const val ACTION_TEST = "ua.vidbiy.TEST"
        const val ACTION_SNOOZE = "ua.vidbiy.SNOOZE"
        const val ACTION_STOP = "ua.vidbiy.STOP"

        private const val POLL_MS = 20_000L
        private const val SNOOZE_MS = 5 * 60_000L
        private const val NO_CONNECTION_MS = 5 * 60_000L
        private const val WAKE_LOCK_MAX_MS = 24 * 60 * 60_000L

        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

        private fun formatTime(millis: Long): String =
            timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun arm(context: Context) {
            val prefs = Prefs(context)
            prefs.armed = true
            prefs.sawAlert = false
            prefs.cutoffAt = prefs.cutoffMinutes.let { if (it >= 0) Prefs.nextOccurrence(it) else 0L }
            WatchRepo.set(WatchState(Phase.WAITING_ALERT, "Перевірка стану тривоги…"))
            ContextCompat.startForegroundService(context, intent(context, ACTION_WATCH))
        }

        fun test(context: Context) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_TEST))

        fun send(context: Context, action: String) {
            context.startService(intent(context, action))
        }

        fun pendingAction(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
                context,
                action.hashCode(),
                intent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun intent(context: Context, action: String) =
            Intent(context, WatchService::class.java).setAction(action)
    }
}
