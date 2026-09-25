package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsState(val prefs: Prefs, private val onScheduleChange: () -> Unit = {}) {
    var region by mutableStateOf(prefs.region)
        private set
    var cutoff by mutableIntStateOf(prefs.cutoffMinutes)
        private set
    var alarmOnNoConnection by mutableStateOf(prefs.alarmOnNoConnection)
        private set
    var source by mutableStateOf(prefs.source)
        private set
    var token by mutableStateOf(prefs.token)
        private set

    var scheduleEnabled by mutableStateOf(prefs.scheduleEnabled)
        private set
    var scheduleMinutes by mutableIntStateOf(prefs.scheduleMinutes)
        private set
    var scheduleDays by mutableIntStateOf(prefs.scheduleDays)
        private set
    var scheduleNext by mutableStateOf(AlarmScheduler.describeNext(prefs))
        private set

    fun updateSchedule(
        enabled: Boolean = scheduleEnabled,
        minutes: Int = scheduleMinutes,
        days: Int = scheduleDays,
    ) {
        prefs.scheduleEnabled = enabled
        prefs.scheduleMinutes = minutes
        prefs.scheduleDays = days
        onScheduleChange()
        reloadSchedule()
    }

    /** Одноразовий будильник вимикається сам, коли спрацює, тож стан треба перечитувати. */
    fun reloadSchedule() {
        scheduleEnabled = prefs.scheduleEnabled
        scheduleMinutes = prefs.scheduleMinutes
        scheduleDays = prefs.scheduleDays
        scheduleNext = AlarmScheduler.describeNext(prefs)
    }

    fun updateRegion(value: Region) {
        region = value
        prefs.region = value
    }

    fun updateCutoff(value: Int) {
        cutoff = value
        prefs.cutoffMinutes = value
    }

    fun updateAlarmOnNoConnection(value: Boolean) {
        alarmOnNoConnection = value
        prefs.alarmOnNoConnection = value
    }

    fun updateSource(value: Source) {
        source = value
        prefs.source = value
    }

    fun updateToken(value: String) {
        token = value
        prefs.token = value
    }

    var soundId by mutableStateOf(prefs.soundId)
        private set
    var soundLabel by mutableStateOf(Sounds.displayName(prefs))
        private set

    fun updateSound(id: String) {
        prefs.soundId = id
        soundId = id
        soundLabel = Sounds.displayName(prefs)
    }

    fun setSystemSound(uri: String, name: String?) {
        prefs.systemSoundUri = uri
        prefs.systemSoundName = name
        updateSound(Sounds.SYSTEM_ID)
    }

    fun setCustomSound(uri: String, name: String?) {
        prefs.customSoundUri = uri
        prefs.customSoundName = name
        updateSound(Sounds.CUSTOM_ID)
    }
}

class Actions(
    val requestNotifications: () -> Unit,
    val openFullScreenSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val arm: () -> Unit,
    val stop: () -> Unit,
    val test: () -> Unit,
    val pickSystemSound: () -> Unit,
    val pickCustomSound: () -> Unit,
    val openExactAlarmSettings: () -> Unit,
)

private enum class Screen { HOME, SETTINGS, REGION, SOUND, SCHEDULE }

@Composable
fun VidbiyApp(prefs: Prefs, settings: SettingsState, permissions: Permissions, actions: Actions) {
    val state by WatchRepo.state.collectAsStateWithLifecycle()
    var onboarded by remember { mutableStateOf(prefs.onboarded) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var regionReturn by remember { mutableStateOf(Screen.HOME) }
    var scheduleReturn by remember { mutableStateOf(Screen.HOME) }
    var dialog by remember { mutableStateOf<AppDialog?>(null) }

    if (!onboarded) {
        Onboarding(settings, permissions, actions) {
            prefs.onboarded = true
            onboarded = true
        }
        return
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = when (screen) {
            Screen.REGION -> regionReturn
            Screen.SOUND -> Screen.SETTINGS
            Screen.SCHEDULE -> scheduleReturn
            else -> Screen.HOME
        }
    }
    val openDialog: (AppDialog) -> Unit = {
        if (it == AppDialog.REGION) {
            regionReturn = screen
            screen = Screen.REGION
        } else if (it == AppDialog.SCHEDULE) {
            scheduleReturn = screen
            screen = Screen.SCHEDULE
        } else if (it == AppDialog.SOUND) {
            screen = Screen.SOUND
        } else {
            dialog = it
        }
    }

    NightBackground {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { current ->
            when (current) {
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    settings = settings,
                    permissions = permissions,
                    actions = actions,
                    onBack = { screen = Screen.HOME },
                    onDialog = openDialog,
                )
                Screen.SCHEDULE -> ScheduleScreen(
                    settings = settings,
                    permissions = permissions,
                    actions = actions,
                    onBack = { screen = scheduleReturn },
                )
                Screen.SOUND -> SoundScreen(
                    settings = settings,
                    actions = actions,
                    onBack = { screen = Screen.SETTINGS },
                )
                Screen.REGION -> RegionScreen(
                    settings = settings,
                    onBack = { screen = regionReturn },
                )
                Screen.HOME -> HomeScreen(
                    state = state,
                    settings = settings,
                    permissions = permissions,
                    actions = actions,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onDialog = openDialog,
                )
            }
        }
    }

    when (dialog) {
        AppDialog.SOURCE -> ChoiceDialog(
            title = "Основне джерело",
            options = Source.entries.map {
                it to if (it == Source.ALERTS_IN_UA && settings.token.isBlank()) "${it.title} (потрібен ключ)" else it.title
            },
            selected = settings.source,
            onSelect = {
                settings.updateSource(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        AppDialog.CUTOFF -> CutoffDialog(
            initialMinutes = settings.cutoff,
            onPick = {
                settings.updateCutoff(it)
                dialog = null
            },
            onClear = {
                settings.updateCutoff(-1)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        AppDialog.REGION, AppDialog.SOUND, AppDialog.SCHEDULE, null -> Unit
    }
}

@Composable
private fun RegionScreen(settings: SettingsState, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
            Text("Регіон", style = MaterialTheme.typography.titleLarge)
        }
        RegionPicker(
            selected = settings.region,
            onPick = {
                settings.updateRegion(it)
                onBack()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

enum class AppDialog { REGION, SOURCE, CUTOFF, SOUND, SCHEDULE }

@Composable
private fun HomeScreen(
    state: WatchState,
    settings: SettingsState,
    permissions: Permissions,
    actions: Actions,
    onOpenSettings: () -> Unit,
    onDialog: (AppDialog) -> Unit,
) {
    val idle = state.phase == Phase.IDLE

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RegionChip(settings.region.name, enabled = idle, onClick = { onDialog(AppDialog.REGION) })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Налаштування", tint = Night.TextDim)
                }
            }

            val (onOrb, hint) = when {
                idle && !permissions.notifications -> actions.requestNotifications to "Торкніться, щоб дозволити сповіщення"
                idle && settings.scheduleEnabled -> actions.arm to "Торкніться, щоб чекати відбою зараз"
                idle -> actions.arm to "Торкніться, щоб увімкнути"
                state.phase == Phase.RINGING || state.phase == Phase.SNOOZED -> actions.stop to "Торкніться, щоб вимкнути"
                else -> actions.stop to "Торкніться, щоб скасувати"
            }
            Hero(
                state,
                onOrb,
                hint,
                settings.scheduleNext.takeIf { settings.scheduleEnabled }
                    ?.let { AlarmScheduler.formatMinutes(settings.scheduleMinutes) to it },
                modifier = Modifier.padding(vertical = 24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionBanner(permissions, actions)
                ScheduleCard(settings, onOpen = { onDialog(AppDialog.SCHEDULE) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoTile(
                        icon = Icons.Rounded.Schedule,
                        label = "Не будити після",
                        value = if (settings.cutoff >= 0) Prefs.formatMinutes(settings.cutoff) else "Без обмеження",
                        enabled = idle,
                        onClick = { onDialog(AppDialog.CUTOFF) },
                        modifier = Modifier.weight(1f),
                    )
                    InfoTile(
                        icon = Icons.Rounded.WifiOff,
                        label = "Якщо зник зв'язок",
                        value = if (settings.alarmOnNoConnection) "Будити" else "Не будити",
                        enabled = idle,
                        onClick = { settings.updateAlarmOnNoConnection(!settings.alarmOnNoConnection) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionChip(name: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Night.Glass,
        border = BorderStroke(1.dp, Night.GlassBorder),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                Icons.Rounded.LocationOn,
                contentDescription = null,
                tint = Night.Amber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (enabled) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Night.TextDim,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun Hero(
    state: WatchState,
    onOrbClick: () -> Unit,
    hint: String,
    /** Час будильника на час і коли він спрацює, якщо його увімкнено. */
    schedule: Pair<String, String>?,
    modifier: Modifier = Modifier,
) {
    val title = when (state.phase) {
        Phase.IDLE -> schedule?.let { "Будильник о ${it.first}" } ?: "Будильник вимкнено"
        Phase.WAITING_ALERT -> "Очікування тривоги"
        Phase.ALERT -> "Триває тривога"
        Phase.RINGING -> "Відбій!"
        Phase.SNOOZED -> "Відкладено"
    }
    val subtitle = when {
        state.phase == Phase.RINGING -> state.reason
        state.text.isNotEmpty() -> state.text
        schedule != null ->
            "Спрацює ${schedule.second.substringBefore(" о ")}. Якщо тоді буде тривога, розбудить після відбою."
        else -> "Якщо тривога застала під час сну, будильник пролунає одразу після відбою."
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        StatusOrb(state.phase, size = 240.dp, onClick = onOrbClick, clickLabel = hint)
        Spacer(Modifier.height(12.dp))
        TouchHint(hint)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Night.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (state.phase != Phase.IDLE && state.source.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val time = DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(state.checkedAt).atZone(ZoneId.systemDefault()))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Night.Green, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.source} · перевірено о $time",
                    style = MaterialTheme.typography.labelMedium,
                    color = Night.TextDim,
                )
            }
        }
    }
}

@Composable
private fun InfoTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        color = Night.Glass,
        border = BorderStroke(1.dp, Night.GlassBorder),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp).alpha(if (enabled) 1f else 0.5f)) {
            Icon(icon, contentDescription = null, tint = Night.Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Night.TextDim)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PermissionBanner(permissions: Permissions, actions: Actions) {
    val missing = buildList {
        if (!permissions.notifications) add("Сповіщення" to actions.requestNotifications)
        if (!permissions.fullScreen) add("Показ на весь екран" to actions.openFullScreenSettings)
        if (!permissions.battery) add("Робота без обмежень батареї" to actions.openBatterySettings)
    }
    if (missing.isEmpty()) return

    GlassCard(color = Night.Amber.copy(alpha = 0.10f), border = Night.Amber.copy(alpha = 0.35f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = Night.Amber,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (!permissions.notifications) "Без сповіщень будильник не спрацює" else "Дозвольте, щоб будильник працював надійно",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Column(Modifier.padding(start = 46.dp, end = 8.dp, bottom = 4.dp)) {
            missing.forEach { (label, onClick) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Night.TextDim,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClick) { Text("Дозволити") }
                }
            }
        }
    }
}
