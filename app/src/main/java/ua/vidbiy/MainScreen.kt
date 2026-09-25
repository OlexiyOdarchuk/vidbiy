package ua.vidbiy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(
    prefs: Prefs,
    permissions: Permissions,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onPickTime: (initialMinutes: Int, onPicked: (Int) -> Unit) -> Unit,
    onArm: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
) {
    val state by WatchRepo.state.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(prefs.token) }
    var regionUid by remember { mutableIntStateOf(prefs.regionUid) }
    var cutoff by remember { mutableIntStateOf(prefs.cutoffMinutes) }
    var alarmOnNoConnection by remember { mutableStateOf(prefs.alarmOnNoConnection) }
    var source by remember { mutableStateOf(prefs.source) }
    var showRegions by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    val idle = state.phase == Phase.IDLE

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("Відбій", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Будильник, що спрацьовує після відбою повітряної тривоги",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StatusCard(state)

            if (idle) {
                Button(
                    onClick = onArm,
                    enabled = permissions.notifications,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Text("Розбудити після відбою", fontSize = 18.sp)
                }
                if (!permissions.notifications) {
                    Hint("Щоб увімкнути будильник, дозвольте сповіщення.")
                }
            } else {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (state.phase == Phase.RINGING || state.phase == Phase.SNOOZED) "Вимкнути будильник" else "Скасувати")
                }
                Hint("Налаштування можна змінити, коли будильник вимкнено.")
            }

            PermissionsCard(permissions, onRequestNotifications, onOpenFullScreenSettings, onOpenBatterySettings)

            SettingsCard(title = "Налаштування", enabled = idle) {
                SettingRow(
                    title = "Регіон",
                    value = Regions.byUid(regionUid).name,
                    enabled = idle,
                    onClick = { showRegions = true },
                )
                HorizontalDivider()
                SettingRow(
                    title = "Не будити після",
                    value = if (cutoff >= 0) Prefs.formatMinutes(cutoff) else "Без обмеження",
                    enabled = idle,
                    onClick = {
                        onPickTime(cutoff) {
                            cutoff = it
                            prefs.cutoffMinutes = it
                        }
                    },
                    trailing = if (cutoff >= 0 && idle) {
                        {
                            TextButton(onClick = {
                                cutoff = -1
                                prefs.cutoffMinutes = -1
                            }) { Text("Прибрати") }
                        }
                    } else null,
                )
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Будити, якщо зник зв'язок")
                        Text(
                            "Сигнал, якщо жодне джерело даних не відповідає понад 5 хвилин",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = alarmOnNoConnection,
                        enabled = idle,
                        onCheckedChange = {
                            alarmOnNoConnection = it
                            prefs.alarmOnNoConnection = it
                        },
                    )
                }
            }

            SettingsCard(title = "Джерела даних", enabled = idle) {
                SettingRow(
                    title = "Основне джерело",
                    value = source.title,
                    enabled = idle,
                    onClick = { showSources = true },
                )
                Hint("Якщо основне джерело недоступне, дані беруться з інших.")
                HorizontalDivider()
                SourcesSection(
                    token = token,
                    regionUid = regionUid,
                    enabled = idle,
                    onTokenChange = {
                        token = it
                        prefs.token = it
                    },
                )
            }

            if (idle) {
                TextButton(onClick = onTest, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Перевірити звук будильника")
                }
            }
        }
    }

    if (showSources) {
        ChoiceDialog(
            title = "Основне джерело",
            options = Source.entries.map {
                it to if (it == Source.ALERTS_IN_UA && token.isBlank()) "${it.title} (потрібен ключ)" else it.title
            },
            selected = source,
            onSelect = {
                source = it
                prefs.source = it
                showSources = false
            },
            onDismiss = { showSources = false },
        )
    }

    if (showRegions) {
        ChoiceDialog(
            title = "Регіон",
            options = Regions.all.map { it.uid to it.name },
            selected = regionUid,
            onSelect = {
                regionUid = it
                prefs.regionUid = it
                showRegions = false
            },
            onDismiss = { showRegions = false },
        )
    }
}

@Composable
private fun StatusCard(state: WatchState) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (state.phase) {
        Phase.IDLE -> scheme.surfaceVariant to scheme.onSurfaceVariant
        Phase.WAITING_ALERT -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Phase.ALERT -> scheme.errorContainer to scheme.onErrorContainer
        Phase.RINGING, Phase.SNOOZED -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    val title = when (state.phase) {
        Phase.IDLE -> "Будильник вимкнено"
        Phase.WAITING_ALERT -> "Очікування тривоги"
        Phase.ALERT -> "Триває тривога"
        Phase.RINGING -> "Будильник дзвонить"
        Phase.SNOOZED -> "Будильник відкладено"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (state.text.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(state.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    permissions: Permissions,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    if (permissions.notifications && permissions.fullScreen && permissions.battery) return
    SettingsCard(title = "Потрібні дозволи", enabled = true) {
        if (!permissions.notifications) {
            PermissionRow("Сповіщення — без них будильник не працює", onRequestNotifications)
        }
        if (!permissions.fullScreen) {
            PermissionRow("Показ будильника на весь екран, коли телефон заблоковано", onOpenFullScreenSettings)
        }
        if (!permissions.battery) {
            PermissionRow("Робота у фоні без обмежень батареї — інакше система може зупинити очікування", onOpenBatterySettings)
        }
    }
}

@Composable
private fun PermissionRow(text: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) { Text("Дозволити") }
    }
}

@Composable
private fun SourcesSection(token: String, regionUid: Int, enabled: Boolean, onTokenChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    Text("Ключ alerts.in.ua (необов'язково)")
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        enabled = enabled,
        singleLine = true,
        label = { Text("Ключ") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Сховати" else "Показати") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    val region = Regions.byUid(regionUid)
                    results = Source.entries.map { source ->
                        val r = withContext(Dispatchers.IO) { AlertsApi.fetchFrom(source, region, token.trim()) }
                        when (r) {
                            is ApiResult.Ok -> {
                                val now = when (r.status) {
                                    AlertStatus.NONE -> "тривоги немає"
                                    AlertStatus.PARTIAL -> "тривога в частині регіону"
                                    AlertStatus.ACTIVE -> "триває тривога"
                                }
                                "${source.title}: працює, $now" to true
                            }
                            is ApiResult.Error -> r.message to false
                        }
                    }
                    checking = false
                }
            },
        ) { Text(if (checking) "Перевірка…" else "Перевірити джерела") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { uriHandler.openUri("https://devs.alerts.in.ua/") }) { Text("Отримати ключ") }
    }
    results.forEach { (text, ok) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SettingsCard(title: String, enabled: Boolean, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(options) { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(value) },
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}
