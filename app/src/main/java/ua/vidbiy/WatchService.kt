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

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var scheduleJob: Job? = null
    private var sunriseJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private lateinit var player: AlarmPlayer
    private lateinit var nm: NotificationManager
    private var announcer: Announcer? = null
    /** Перевірка звуку з налаштувань: не пишеться в історію й не надсилає вебхуків. */
    private var testing = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        RegionTreeRepo.init(this)
        player = AlarmPlayer(this)
        nm = getSystemService(NotificationManager::class.java)
        Notifications.createChannels(this)
        // Плитка й віджет показують той самий стан, що й програма.
        scope.launch { WatchRepo.state.collect { Surfaces.refresh(this@WatchService) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCH -> {
                goForeground("Перевірка стану тривоги…")
                startWatching()
            }

            ACTION_SCHEDULED -> {
                val at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis())
                goForeground("Будильник о ${formatTime(at)}: перевірка тривоги…")
                scheduled(intent.getIntExtra(EXTRA_ALARM, 0), at, intent.getBooleanExtra(EXTRA_RULES, false))
            }

            ACTION_SUNRISE -> {
                val at = intent.getLongExtra(EXTRA_AT, 0L)
                goForeground("Світанок перед будильником о ${formatTime(at)}")
                sunrise(intent.getIntExtra(EXTRA_ALARM, 0), at, intent.getBooleanExtra(EXTRA_RULES, false))
            }

            ACTION_TOGGLE -> {
                goForeground("Перевірка стану тривоги…")
                when {
                    WatchRepo.state.value.phase == Phase.IDLE && job?.isActive != true -> {
                        prepareArm(this)
                        startWatching()
                    }
                    // Сигнал із завданням не вимикається з віджета — відкриваємо екран сигналу.
                    WatchRepo.state.value.phase == Phase.RINGING && prefs.dismissTask != DismissTask.NONE ->
                        startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else -> finish(null)
                }
            }

            ACTION_TEST -> {
                goForeground("Перевірка звуку")
                job?.cancel()
                testing = true
                ring("Перевірка будильника", log = false)
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
        announcer?.release()
        player.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /** Спрацював будильник на час: спершу правила нічної тривоги, потім звичайна перевірка. */
    private fun scheduled(alarmId: Int, at: Long, applyRules: Boolean) {
        val label = formatTime(at)
        scheduleJob?.cancel()
        scheduleJob = scope.launch {
            val alarm = prefs.alarms.firstOrNull { it.id == alarmId } ?: Alarm(id = alarmId)
            val place = alarm.place ?: prefs.region
            val rules = prefs.nightRules.filter { it.enabled }
            if (applyRules && rules.isNotEmpty()) {
                val history = withContext(Dispatchers.IO) { NightRules.history(place) }
                val outcome = history?.let { NightRules.evaluate(rules, at, it) } ?: RuleOutcome.None
                val message = when (outcome) {
                    is RuleOutcome.Shift -> {
                        prefs.shiftAt = outcome.at
                        prefs.shiftAlarmId = alarm.id
                        AlarmScheduler.schedule(this@WatchService)
                        "Будильник о $label перенесено на ${formatTime(outcome.at)}: " +
                            "уночі тривога тривала ${NightRules.formatDurationMs(outcome.alertMs)}"
                    }
                    is RuleOutcome.Skip ->
                        "Будильник о $label сьогодні не дзвонитиме: " +
                            "уночі тривога тривала ${NightRules.formatDurationMs(outcome.alertMs)}"
                    RuleOutcome.None -> null
                }
                if (message != null) {
                    val type = if (outcome is RuleOutcome.Skip) "skip" else "shift"
                    if (WatchRepo.state.value.phase == Phase.IDLE) History.note(this@WatchService, message, type)
                    else History.add(this@WatchService, message, type)
                    nm.notify(Notifications.ID_INFO, Notifications.info(this@WatchService, message))
                    scheduleJob = null
                    stopIfIdle()
                    return@launch
                }
            }
            scheduleJob = null
            beginScheduledRun(place, label)
        }
    }

    private fun beginScheduledRun(place: Region, label: String) {
        val phase = WatchRepo.state.value.phase
        if (phase == Phase.RINGING || phase == Phase.SNOOZED) return
        val current = prefs.runPlace ?: prefs.region
        // Стан у пам'яті, а не prefs.armed: після перезавантаження там могла лишитися позначка з минулої ночі.
        if (phase == Phase.IDLE || current != place) prefs.sawAlert = false
        if (phase == Phase.IDLE) {
            prefs.cutoffAt = prefs.cutoffMinutes.let { if (it >= 0) Prefs.nextOccurrence(it) else 0L }
            History.begin(this, "Будильник о $label: перевірка тривоги", "scheduled")
            Webhooks.fire(this, HookEvent.ARMED, place.name, "Будильник о $label")
        } else {
            History.add(this, "Будильник о $label: перевірка тривоги", "scheduled")
        }
        prefs.armed = true
        prefs.scheduleRun = true
        prefs.scheduleLabel = label
        prefs.runPlace = place.takeIf { it != prefs.region }
        WatchRepo.set(WatchState(Phase.WAITING_ALERT, "Будильник о $label: перевірка тривоги…"))
        startWatching()
    }

    /** Світанок показуємо, лише якщо будильник справді продзвонить у свій час. */
    private fun sunrise(alarmId: Int, at: Long, applyRules: Boolean) {
        sunriseJob?.cancel()
        sunriseJob = scope.launch {
            val alarm = prefs.alarms.firstOrNull { it.id == alarmId } ?: Alarm(id = alarmId)
            val place = alarm.place ?: prefs.region
            val rules = prefs.nightRules.filter { it.enabled }
            var show = WatchRepo.state.value.phase.let { it == Phase.IDLE || it == Phase.WAITING_ALERT }
            if (show && applyRules && alarm.nightRule && rules.isNotEmpty()) {
                val history = withContext(Dispatchers.IO) { NightRules.history(place) }
                if (history != null && NightRules.evaluate(rules, at, history) != RuleOutcome.None) show = false
            }
            if (show) {
                val result = withContext(Dispatchers.IO) { AlertsApi.fetch(prefs.source, place, prefs.token) }
                if (result is ApiResult.Ok && result.status != AlertStatus.NONE) show = false
            }
            if (show) nm.notify(Notifications.ID_SUNRISE, Notifications.sunrise(this@WatchService, at))
            sunriseJob = null
            stopIfIdle()
        }
    }

    private fun startWatching() {
        job?.cancel()
        job = scope.launch {
            val region = prefs.runPlace ?: prefs.region
            val startedAt = System.currentTimeMillis()
            var lastOk = startedAt
            var phase = if (prefs.sawAlert) Phase.ALERT else Phase.WAITING_ALERT
            var lastStatus: AlertStatus? = null
            var clearSince = 0L
            var offlineNoted = false

            while (isActive) {
                val cutoff = prefs.cutoffAt
                if (cutoff > 0 && System.currentTimeMillis() >= cutoff) {
                    finish("Настав час ${formatTime(cutoff)} — будильник вимкнено без сигналу")
                    return@launch
                }

                val result = withContext(Dispatchers.IO) { AlertsApi.fetch(prefs.source, region, prefs.token) }
                when (result) {
                    is ApiResult.Ok -> {
                        Widgets.noteStatus(this@WatchService, region, result.status)
                        lastOk = System.currentTimeMillis()
                        offlineNoted = false
                        val via = result.source.title
                        if (result.status != AlertStatus.NONE) {
                            val partial = result.status == AlertStatus.PARTIAL
                            when {
                                lastStatus == null -> {
                                    History.add(this@WatchService, if (partial) "Тривога в частині регіону" else "Триває тривога", "alert")
                                    Webhooks.fire(this@WatchService, HookEvent.ALERT, region.name, "Триває тривога")
                                }
                                lastStatus == AlertStatus.NONE -> {
                                    History.add(
                                        this@WatchService,
                                        if (clearSince > 0) "Тривога повторилася" else if (partial) "Тривога в частині регіону" else "Почалася тривога",
                                        "alert",
                                    )
                                    Webhooks.fire(this@WatchService, HookEvent.ALERT, region.name, "Почалася тривога")
                                    if (prefs.alertStartNotice) {
                                        nm.notify(Notifications.ID_ALERT_START, Notifications.alertStart(this@WatchService, region.name))
                                    }
                                }
                            }
                            clearSince = 0L
                            prefs.sawAlert = true
                            phase = Phase.ALERT
                            val text = when {
                                prefs.scheduleRun && partial ->
                                    "Будильник о ${prefs.scheduleLabel} чекає: тривога в частині регіону. Розбудить після відбою в усьому регіоні."
                                prefs.scheduleRun ->
                                    "Будильник о ${prefs.scheduleLabel} чекає: триває тривога. Розбудить після відбою."
                                partial ->
                                    "Тривога в частині регіону. Будильник пролунає після відбою в усьому регіоні."
                                else -> "Будильник пролунає після відбою."
                            }
                            update(phase, text, via)
                        } else if (prefs.sawAlert) {
                            val now = System.currentTimeMillis()
                            if (clearSince == 0L) {
                                clearSince = now
                                History.add(this@WatchService, "Відбій тривоги", "clear")
                                Webhooks.fire(this@WatchService, HookEvent.CLEAR, region.name, "Відбій тривоги")
                            }
                            // Тривога часто повторюється за кілька хвилин, тож за бажанням чекаємо, щоб відбій утримався.
                            val stableMs = prefs.stableClearMinutes * 60_000L
                            if (now - clearSince >= stableMs) {
                                ring("Відбій тривоги: ${region.name}")
                                return@launch
                            }
                            phase = Phase.CLEARING
                            update(
                                phase,
                                "Відбій о ${formatTime(clearSince)}. Розбудить о ${formatTime(clearSince + stableMs)}, якщо тривога не повториться.",
                                via,
                            )
                        } else if (prefs.scheduleRun) {
                            // Настав час будильника, а тривоги немає — будимо, як звичайний будильник.
                            ring("Будильник о ${prefs.scheduleLabel}")
                            return@launch
                        } else {
                            phase = Phase.WAITING_ALERT
                            update(phase, "Зараз тривоги немає. Будильник пролунає після відбою наступної тривоги.", via)
                        }
                        lastStatus = result.status
                    }

                    is ApiResult.Error -> {
                        if (!offlineNoted) {
                            History.add(this@WatchService, "Немає зв'язку з джерелами даних", "offline")
                            offlineNoted = true
                        }
                        // Не знаємо, чи є тривога, — краще розбудити, ніж проспати.
                        if (prefs.scheduleRun && !prefs.sawAlert &&
                            System.currentTimeMillis() - startedAt >= SCHEDULE_CHECK_MS
                        ) {
                            ring("Будильник о ${prefs.scheduleLabel} (не вдалося перевірити тривогу)")
                            return@launch
                        }
                        // Уночі перед будильником на час не будимо через зв'язок: він і так спрацює в заданий час.
                        val wakeAt = if (prefs.scheduleRun) null else upcomingSchedule()
                        val offline = System.currentTimeMillis() - lastOk
                        if (prefs.alarmOnNoConnection && wakeAt == null && offline >= NO_CONNECTION_MS) {
                            ring("Понад ${NO_CONNECTION_MS / 60_000} хв немає зв'язку з жодним джерелом даних про тривоги")
                            return@launch
                        }
                        val retry = wakeAt?.let { "Будильник спрацює о ${formatTime(it)}. Повторна спроба…" }
                            ?: "Повторна спроба…"
                        update(phase, "${result.message}. $retry")
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    /** Час найближчого будильника на час, якщо він спрацює протягом доби. */
    private fun upcomingSchedule(): Long? {
        val at = AlarmScheduler.next(prefs)?.first ?: return null
        return at.takeIf { it - System.currentTimeMillis() <= SCHEDULE_COVERS_MS }
    }

    private fun ring(reason: String, log: Boolean = true) {
        acquireWakeLock()
        if (log) {
            History.add(this, "Сигнал: $reason", "ring")
            Webhooks.fire(this, HookEvent.RING, (prefs.runPlace ?: prefs.region).name, reason)
        }
        nm.cancel(Notifications.ID_SUNRISE)
        WatchRepo.set(WatchState(Phase.RINGING, reason, reason))
        val onWatch = prefs.watchVibrate
        val withTask = prefs.dismissTask != DismissTask.NONE
        nm.notify(Notifications.ID_ALARM, Notifications.alarm(this, reason, localOnly = onWatch, withTask = withTask))
        if (onWatch) nm.notify(Notifications.ID_WEAR, Notifications.wearAlarm(this, reason, withTask))
        player.start(scope)
        if (prefs.voice) {
            val a = announcer ?: Announcer(this) { player.duck(it) }.also { announcer = it }
            a.start(scope, reason)
        }
        startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun snooze() {
        val state = WatchRepo.state.value
        if (state.phase != Phase.RINGING) {
            if (job == null) stopSelf()
            return
        }
        player.stop()
        announcer?.stop()
        nm.cancel(Notifications.ID_ALARM)
        nm.cancel(Notifications.ID_WEAR)
        val at = System.currentTimeMillis() + SNOOZE_MS
        val text = "Повторний сигнал о ${formatTime(at)}"
        History.add(this, "Відкладено до ${formatTime(at)}", "snooze")
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
        job = null
        val wasActive = !testing && (prefs.armed || WatchRepo.state.value.phase != Phase.IDLE)
        testing = false
        player.stop()
        announcer?.stop()
        nm.cancel(Notifications.ID_ALARM)
        nm.cancel(Notifications.ID_WEAR)
        nm.cancel(Notifications.ID_SUNRISE)
        prefs.armed = false
        prefs.sawAlert = false
        prefs.scheduleRun = false
        val place = (prefs.runPlace ?: prefs.region).name
        prefs.runPlace = null
        History.end(this, message ?: "Вимкнено", if (message != null) "cutoff" else "stop")
        if (wasActive) Webhooks.fire(this, HookEvent.STOP, place, message ?: "Вимкнено")
        WatchRepo.set(WatchState(Phase.IDLE, message ?: ""))
        if (message != null) nm.notify(Notifications.ID_INFO, Notifications.info(this, message))
        Surfaces.refresh(this)
        stopIfIdle()
    }

    /** Зупиняє сервіс, коли не лишилося ні очікування, ні перевірок будильника на час чи світанку. */
    private fun stopIfIdle() {
        val busy = job?.isActive == true || scheduleJob?.isActive == true || sunriseJob?.isActive == true
        if (busy || WatchRepo.state.value.phase != Phase.IDLE) {
            // Сповіщення очікування могло тимчасово показувати іншу перевірку — повертаємо його текст.
            val text = WatchRepo.state.value.text
            if (!busy && text.isNotEmpty()) nm.notify(Notifications.ID_WATCH, Notifications.watch(this, text))
            return
        }
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
        const val ACTION_SCHEDULED = "ua.vidbiy.SCHEDULED"
        const val ACTION_SUNRISE = "ua.vidbiy.SUNRISE_CHECK"
        const val ACTION_TOGGLE = "ua.vidbiy.TOGGLE"

        private const val EXTRA_ALARM = "alarm"
        private const val EXTRA_AT = "at"
        private const val EXTRA_RULES = "rules"

        private const val POLL_MS = 20_000L
        private const val SNOOZE_MS = 5 * 60_000L
        private const val NO_CONNECTION_MS = 5 * 60_000L
        private const val SCHEDULE_CHECK_MS = 60_000L
        private const val SCHEDULE_COVERS_MS = 24 * 60 * 60_000L
        private const val WAKE_LOCK_MAX_MS = 24 * 60 * 60_000L

        private fun formatTime(millis: Long): String = AlarmScheduler.formatTime(millis)

        /** Готує стан до очікування «чекати відбою зараз». */
        private fun prepareArm(context: Context) {
            val prefs = Prefs(context)
            prefs.armed = true
            prefs.sawAlert = false
            prefs.scheduleRun = false
            prefs.runPlace = null
            prefs.cutoffAt = prefs.cutoffMinutes.let { if (it >= 0) Prefs.nextOccurrence(it) else 0L }
            History.begin(context, "Очікування відбою увімкнено", "armed")
            Webhooks.fire(context, HookEvent.ARMED, prefs.region.name, "Очікування відбою")
            WatchRepo.set(WatchState(Phase.WAITING_ALERT, "Перевірка стану тривоги…"))
        }

        fun arm(context: Context) {
            prepareArm(context)
            ContextCompat.startForegroundService(context, intent(context, ACTION_WATCH))
        }

        /** Запуск від будильника на час. Якщо очікування вже йде, лише перемикає його в цей режим. */
        fun startScheduled(context: Context, alarm: Alarm, at: Long, applyRules: Boolean) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_SCHEDULED)
                    .putExtra(EXTRA_ALARM, alarm.id)
                    .putExtra(EXTRA_AT, at)
                    .putExtra(EXTRA_RULES, applyRules),
            )
        }

        fun sunrise(context: Context, at: Long, alarmId: Int, applyRules: Boolean) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_SUNRISE)
                    .putExtra(EXTRA_ALARM, alarmId)
                    .putExtra(EXTRA_AT, at)
                    .putExtra(EXTRA_RULES, applyRules),
            )
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

        /** Для віджета: вмикає або вимикає очікування. */
        fun togglePending(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                ACTION_TOGGLE.hashCode(),
                intent(context, ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun intent(context: Context, action: String) =
            Intent(context, WatchService::class.java).setAction(action)
    }
}
