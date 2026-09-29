package ua.vidbiy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    state: WatchState,
    settings: SettingsState,
    permissions: Permissions,
    actions: Actions,
    onBack: () -> Unit,
    onDialog: (AppDialog) -> Unit,
) {
    val idle = state.phase == Phase.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.width(4.dp))
            Text("Налаштування", style = MaterialTheme.typography.titleLarge)
        }

        if (!idle) {
            Spacer(Modifier.height(12.dp))
            GlassCard(color = Night.Blue.copy(alpha = 0.10f), border = Night.Blue.copy(alpha = 0.30f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = Night.Blue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Налаштування можна змінити, коли будильник вимкнено.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SectionHeader("Будильник")
        GlassCard {
            SettingsRow(
                icon = Icons.Rounded.Alarm,
                title = "Будильники на час",
                value = settings.alarms.filter { it.enabled }.sortedBy { it.minutes }.let { on ->
                    when (on.size) {
                        0 -> "Вимкнено"
                        1 -> "${AlarmScheduler.formatMinutes(on[0].minutes)} · ${AlarmScheduler.describeDays(on[0].days)}"
                        else -> on.joinToString(", ") { AlarmScheduler.formatMinutes(it.minutes) }
                    }
                },
                onClick = { onDialog(AppDialog.SCHEDULE) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.LocationOn,
                title = "Регіон",
                value = listOfNotNull(settings.region.name, settings.region.detail).joinToString(", "),
                enabled = idle,
                onClick = { onDialog(AppDialog.REGION) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.Schedule,
                title = "Не будити після",
                value = if (settings.cutoff >= 0) Prefs.formatMinutes(settings.cutoff) else "Без обмеження",
                enabled = idle,
                onClick = { onDialog(AppDialog.CUTOFF) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.HourglassTop,
                title = "Чекати, щоб відбій утримався",
                value = if (settings.stableClearMinutes == 0) {
                    "Ні, будити одразу після відбою"
                } else {
                    "${settings.stableClearMinutes} хв: якщо тривога повториться, будильник чекатиме далі"
                },
                enabled = idle,
                onClick = { onDialog(AppDialog.STABLE) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = "Звук будильника",
                value = settings.soundLabel,
                enabled = idle,
                onClick = { onDialog(AppDialog.SOUND) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.WifiOff,
                title = "Будити, якщо зник зв'язок",
                value = "Якщо жодне джерело не відповідає понад 5 хвилин",
                enabled = idle,
                onClick = { settings.updateAlarmOnNoConnection(!settings.alarmOnNoConnection) },
                trailing = {
                    Switch(
                        checked = settings.alarmOnNoConnection,
                        enabled = idle,
                        onCheckedChange = settings::updateAlarmOnNoConnection,
                        colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
                    )
                },
            )
        }

        SectionHeader("Пробудження")
        GlassCard {
            SettingsRow(
                icon = Icons.Rounded.DarkMode,
                title = "Правила нічної тривоги",
                value = settings.nightRules.count { it.enabled }.let {
                    if (it == 0) "Будити пізніше, якщо вночі була тривога" else "Увімкнено ${rulesCount(it)}"
                },
                onClick = { onDialog(AppDialog.RULES) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.WbTwilight,
                title = "Світанок перед будильником",
                value = if (settings.sunriseMinutes == 0) {
                    "Вимкнено"
                } else {
                    "За ${settings.sunriseMinutes} хв до будильника на час екран поволі світлішає"
                },
                onClick = { onDialog(AppDialog.SUNRISE) },
            )
            RowDivider()
            SwitchRow(
                icon = Icons.Rounded.Vibration,
                title = "Повідомляти про початок тривоги",
                value = "Тихо, лише вібрацією, поки будильник чекає відбою",
                checked = settings.alertStartNotice,
                onChange = settings::updateAlertStartNotice,
            )
            RowDivider()
            SwitchRow(
                icon = Icons.Rounded.Watch,
                title = "Вібрація на годиннику",
                value = "Сигнал будильника на годиннику, підключеному до телефона",
                checked = settings.watchVibrate,
                onChange = settings::updateWatchVibrate,
            )
            RowDivider()
            SwitchRow(
                icon = Icons.Rounded.BatteryAlert,
                title = "Перевірка перед сном",
                value = "Попередить про низький заряд, «Не турбувати» чи відсутність інтернету",
                checked = settings.bedtimeCheck,
                onChange = settings::updateBedtimeCheck,
            )
        }

        SectionHeader("Джерела даних")
        GlassCard {
            SettingsRow(
                icon = Icons.Rounded.Dns,
                title = "Основне джерело",
                value = settings.source.title,
                enabled = idle,
                onClick = { onDialog(AppDialog.SOURCE) },
            )
            Text(
                "Якщо основне джерело недоступне, дані беруться з інших.",
                style = MaterialTheme.typography.bodySmall,
                color = Night.TextDim,
                modifier = Modifier.padding(start = 70.dp, end = 16.dp, bottom = 12.dp),
            )
            RowDivider()
            KeyAndCheck(settings, enabled = idle)
        }

        SectionHeader("Дозволи")
        GlassCard {
            PermissionRow(Icons.Rounded.Notifications, "Сповіщення", permissions.notifications, actions.requestNotifications)
            RowDivider()
            PermissionRow(Icons.Rounded.Fullscreen, "Показ на весь екран", permissions.fullScreen, actions.openFullScreenSettings)
            RowDivider()
            PermissionRow(
                Icons.Rounded.BatteryChargingFull,
                "Робота без обмежень батареї",
                permissions.battery,
                actions.openBatterySettings,
            )
        }

        SectionHeader("Інше")
        GlassCard {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = "Перевірити звук будильника",
                enabled = idle,
                onClick = actions.test,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.SystemUpdate,
                title = "Оновлюватися автоматично",
                value = "Нові версії з GitHub встановлюються, коли будильник вимкнено",
                onClick = { settings.updateAutoUpdate(!settings.autoUpdate) },
                trailing = {
                    Switch(
                        checked = settings.autoUpdate,
                        onCheckedChange = settings::updateAutoUpdate,
                        colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
                    )
                },
            )
            RowDivider()
            val update by Updater.state.collectAsStateWithLifecycle()
            SettingsRow(
                icon = Icons.Rounded.Refresh,
                title = "Перевірити оновлення",
                value = when (val u = update) {
                    UpdateState.Checking -> "Перевірка…"
                    UpdateState.UpToDate -> "Встановлено найновішу версію ${Updater.currentVersion}"
                    is UpdateState.Available -> "Доступна версія ${u.version}"
                    is UpdateState.Downloading -> "Завантаження версії ${u.version}…"
                    is UpdateState.Installing -> "Встановлення версії ${u.version}…"
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> "Версія ${Updater.currentVersion}"
                },
                enabled = idle,
                onClick = if (update is UpdateState.Available) actions.update else actions.checkUpdate,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.School,
                title = "Знайомство з програмою",
                value = "Короткий огляд можливостей",
                onClick = { onDialog(AppDialog.TOUR) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Rounded.History,
                title = "Історія",
                value = "Коли були тривога й відбій і чому дзвонив будильник",
                onClick = { onDialog(AppDialog.HISTORY) },
            )
            RowDivider()
            val uriHandler = LocalUriHandler.current
            SettingsRow(
                icon = Icons.Rounded.Code,
                title = "Відкритий код на GitHub",
                value = "Код, повідомлення про помилки й ідеї",
                onClick = { uriHandler.openUri("https://github.com/OlexiyOdarchuk/vidbiy") },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        value = value,
        onClick = { onChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
            )
        },
    )
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, granted: Boolean, onGrant: () -> Unit) {
    SettingsRow(
        icon = icon,
        title = title,
        trailing = {
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Night.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Надано", style = MaterialTheme.typography.labelLarge, color = Night.Green)
                }
            } else {
                TextButton(onClick = onGrant) { Text("Дозволити") }
            }
        },
    )
}

@Composable
private fun KeyAndCheck(settings: SettingsState, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Key)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Ключ alerts.in.ua", style = MaterialTheme.typography.bodyLarge)
                Text("Необов'язково", style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
            }
            TextButton(onClick = { uriHandler.openUri("https://devs.alerts.in.ua/") }) { Text("Отримати") }
        }
        OutlinedTextField(
            value = settings.token,
            onValueChange = settings::updateToken,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("Вставте ключ") },
            shape = RoundedCornerShape(16.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                if (settings.token.isNotEmpty()) {
                    TextButton(onClick = { visible = !visible }) { Text(if (visible) "Сховати" else "Показати") }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Night.GlassBorder,
                focusedBorderColor = Night.Amber,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            enabled = !checking,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Night.Amber.copy(alpha = 0.16f),
                contentColor = Night.Amber,
            ),
            onClick = {
                checking = true
                scope.launch {
                    results = Source.entries.map { source ->
                        val r = withContext(Dispatchers.IO) {
                            AlertsApi.fetchFrom(source, settings.region, settings.token.trim())
                        }
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
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checking) "Перевірка…" else "Перевірити джерела") }

        results.forEach { (text, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) Night.Green else Night.Red,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
