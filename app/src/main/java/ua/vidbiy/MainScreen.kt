package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsState(private val prefs: Prefs) {
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
}

class Actions(
    val requestNotifications: () -> Unit,
    val openFullScreenSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val arm: () -> Unit,
    val stop: () -> Unit,
    val test: () -> Unit,
)

private enum class Screen { HOME, SETTINGS, REGION }

@Composable
fun VidbiyApp(prefs: Prefs, permissions: Permissions, actions: Actions) {
    val settings = remember { SettingsState(prefs) }
    val state by WatchRepo.state.collectAsStateWithLifecycle()
    var onboarded by remember { mutableStateOf(prefs.onboarded) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var regionReturn by remember { mutableStateOf(Screen.HOME) }
    var dialog by remember { mutableStateOf<AppDialog?>(null) }

    if (!onboarded) {
        Onboarding(settings, permissions, actions) {
            prefs.onboarded = true
            onboarded = true
        }
        return
    }

    DemoGayMapNotifications(permissions.notifications)

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.REGION) regionReturn else Screen.HOME
    }
    val openDialog: (AppDialog) -> Unit = {
        if (it == AppDialog.REGION) {
            regionReturn = screen
            screen = Screen.REGION
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

        AppDialog.REGION, null -> Unit
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

enum class AppDialog { REGION, SOURCE, CUTOFF }

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
                idle -> actions.arm to "Торкніться, щоб ввімкнути режим пошуку підарасів"
                state.phase == Phase.RINGING || state.phase == Phase.SNOOZED -> actions.stop to "Торкніться, щоб вимкнути"
                else -> actions.stop to "Торкніться, щоб скасувати"
            }
            Hero(state, onOrb, hint, modifier = Modifier.padding(vertical = 24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionBanner(permissions, actions)
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
                RetroMemeStrip()
                PowerMeter()
                NearbyDemoMap()
            }
        }
    }
}

@Composable
private fun RetroMemeStrip() {
    GlassCard(color = Night.Glass, border = Night.GlassBorder) {
        Column(Modifier.padding(10.dp)) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("67", "1488", "67").forEach { meme ->
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = Night.Blue,
                        border = BorderStroke(1.dp, Night.Amber),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            meme,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyDemoMap() {
    var selected by remember { mutableStateOf<String?>(null) }
    val markers = listOf(
        Triple("Дядя Богдан", 34.dp, 40.dp),
        Triple("Danger master", 150.dp, 92.dp),
        Triple("Біллі Херінгтон", 245.dp, 28.dp),
        Triple("Веселковий NPC", 285.dp, 135.dp),
    )
    GlassCard(color = Color(0xFF00FFB7), border = Color(0xFFFF0055)) {
        Column(Modifier.padding(10.dp)) {
            Text("ГЕЙ-КАРТА", style = MaterialTheme.typography.labelLarge, color = Color.Black)
            Text(
                "Натискайте на маркери, щоб обрати вигаданого персонажа",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF8AE6FF)),
            ) {
                Text("КАРТА", color = Color(0xFF1200A8), modifier = Modifier.padding(8.dp))
                DemoMapMarker("A", 34.dp, 40.dp, Color(0xFFFF0055)) { selected = markers[0].first }
                DemoMapMarker("B", 150.dp, 92.dp, Color(0xFF7A00FF)) { selected = markers[1].first }
                DemoMapMarker("C", 245.dp, 28.dp, Color(0xFFFF6600)) { selected = markers[2].first }
                DemoMapMarker("D", 285.dp, 135.dp, Color(0xFF00A83B)) { selected = markers[3].first }
                Surface(
                    color = Color(0xFFFFEA00),
                    border = BorderStroke(2.dp, Color.Black),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp),
                ) {}
            }
            if (selected != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$selected — хоче вас",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Black,
                )
            }
        }
    }
}

@Composable
private fun DemoGayMapNotifications(enabled: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val messages = listOf(
            "Гей — 300 м від вас",
            "Гей за вами спостерігає",
            "Гей наближається",
            "Гей активував веселковий режим",
            "Гей помічений неподалік",
            "Гей дивиться прямо на вас",
            "Гей шукає пригоди",
            "Дядя Богдан — 300 м від вас",
            "Дядя Богдан прямує у вашу сторону",
            "Дядя Богдан щойно зайшов у чат",
            "Дядя Богдан рекомендує зайти в гості",
            "Біллі Херінгтон задивляється на вас",
            "Біллі Херінгтон у режимі очікування неподалік",
            "Біллі Херінгтон схвалює ваш маршрут",
            "Ван Дамм проїжджав повз — привітався",
            "Сусід Толя знову вийшов на балкон",
            "Хтось поставив лайк вашій геолокації",
            "Рівень веселки в районі зашкалює",
            "Активність зафіксовано в радіусі 500 м",
            "У вашому районі стало на 1 гея більше",
        )
        while (true) {
            delay(Random.nextLong(45_000L, 90_001L))
            Notifications.info(context, messages.random()).let {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .notify(Notifications.ID_INFO, it)
            }
        }
    }
}

@Composable
private fun BoxScope.DemoMapMarker(label: String, x: Dp, y: Dp, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = color,
        border = BorderStroke(2.dp, Color.Black),
        shape = CircleShape,
        modifier = Modifier
            .offset(x = x, y = y)
            .size(28.dp),
    ) {
        Text(
            label,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PowerMeter() {
    GlassCard(color = Color(0xFFFF00FF), border = Color(0xFF00FF00)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ПОТУЖНОМЕТР", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                Spacer(Modifier.weight(1f))
                Text("999%", style = MaterialTheme.typography.titleMedium, color = Color.Black)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    Color.Red,
                    Color(0xFFFF6600),
                    Color.Yellow,
                    Color.Green,
                    Color.Cyan,
                    Color.Blue,
                    Color.Magenta,
                    Color.Red,
                    Color.Yellow,
                    Color.Green,
                    Color.Cyan,
                    Color.Magenta,
                ).forEach { color ->
                    Surface(
                        color = color,
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.weight(1f).height(18.dp),
                    ) {}
                }
            }
            Text(
                "Вимірює приблизно все і нічого",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black,
            )
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
private fun Hero(state: WatchState, onOrbClick: () -> Unit, hint: String, modifier: Modifier = Modifier) {
    val title = when (state.phase) {
        Phase.IDLE -> "Геї не знайдені"
        Phase.WAITING_ALERT -> "Очікування пенетрації"
        Phase.ALERT -> "Триває секс"
        Phase.RINGING -> "Кінчив!"
        Phase.SNOOZED -> "Джунжурик впав!"
    }
    val subtitle = when {
        state.phase == Phase.RINGING -> state.reason
        state.text.isNotEmpty() -> state.text
        else -> "Якщо підарас застів під час сну, будильник пролунає одразу після сексу."
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
