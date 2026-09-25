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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
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
                icon = Icons.Rounded.LocationOn,
                title = "Регіон",
                value = settings.region.name,
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
        }
        Spacer(Modifier.height(24.dp))
    }
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
