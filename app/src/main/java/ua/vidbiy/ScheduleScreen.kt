package ua.vidbiy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val dayLetters = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

/** Картка на головному екрані: час будильника, дні й перемикач. */
@Composable
fun ScheduleCard(settings: SettingsState, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        color = Night.Glass,
        border = BorderStroke(1.dp, Night.GlassBorder),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            IconBadge(Icons.Rounded.Alarm)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Будильник на час", style = MaterialTheme.typography.labelMedium, color = Night.TextDim)
                if (settings.scheduleEnabled) {
                    Text(AlarmScheduler.formatMinutes(settings.scheduleMinutes), style = MaterialTheme.typography.titleLarge)
                    Text(
                        AlarmScheduler.describeDays(settings.scheduleDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim,
                    )
                } else {
                    Text("Вимкнено", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Розбудить у заданий час, а під час тривоги — після відбою",
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.scheduleEnabled,
                onCheckedChange = { settings.updateSchedule(enabled = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
            )
        }
    }
}

@Composable
fun ScheduleScreen(settings: SettingsState, permissions: Permissions, actions: Actions, onBack: () -> Unit) {
    val context = LocalContext.current
    var pickTime by remember { mutableStateOf(false) }
    val exactAllowed = remember(permissions) { AlarmScheduler.canScheduleExact(context) }
    val enabled = settings.scheduleEnabled

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Будильник на час", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { settings.updateSchedule(enabled = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
            )
        }

        // Великий час: натиснути, щоб змінити
        Text(
            AlarmScheduler.formatMinutes(settings.scheduleMinutes),
            fontSize = 88.sp,
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clickable { pickTime = true },
        )
        Text(
            if (enabled) settings.scheduleNext?.let { "Спрацює $it" } ?: "" else "Будильник вимкнено",
            style = MaterialTheme.typography.bodyLarge,
            color = Night.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { pickTime = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Змінити час")
        }

        SectionHeader("Дні")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    dayLetters.forEachIndexed { i, letter ->
                        val bit = 1 shl i
                        val on = settings.scheduleDays and bit != 0
                        Surface(
                            onClick = { settings.updateSchedule(days = settings.scheduleDays xor bit) },
                            shape = CircleShape,
                            color = if (on) Night.Amber else Night.Glass,
                            border = BorderStroke(1.dp, if (on) Night.Amber else Night.GlassBorder),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Text(
                                letter,
                                color = if (on) Night.OnAmber else Night.Text,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "Будні" to AlarmScheduler.WEEKDAYS,
                        "Щодня" to AlarmScheduler.EVERY_DAY,
                        "Вихідні" to AlarmScheduler.WEEKEND,
                        "Один раз" to 0,
                    ).forEach { (label, days) ->
                        TextButton(onClick = { settings.updateSchedule(days = days) }) {
                            Text(label, color = if (settings.scheduleDays == days) Night.Amber else Night.TextDim)
                        }
                    }
                }
                val presets = listOf(AlarmScheduler.WEEKDAYS, AlarmScheduler.EVERY_DAY, AlarmScheduler.WEEKEND)
                if (settings.scheduleDays !in presets) {
                    Text(
                        if (settings.scheduleDays == 0) {
                            "Один раз: після спрацювання будильник вимкнеться"
                        } else {
                            AlarmScheduler.describeDays(settings.scheduleDays)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim,
                    )
                }
            }
        }

        if (!exactAllowed) {
            Spacer(Modifier.height(12.dp))
            GlassCard(color = Night.Amber.copy(alpha = 0.10f), border = Night.Amber.copy(alpha = 0.35f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Night.Amber)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Дозвольте точні будильники, інакше система може спрацювати із запізненням.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions.openExactAlarmSettings) { Text("Дозволити") }
                }
            }
        }

        SectionHeader("Як це працює")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "У заданий час програма перевіряє, чи є тривога у вашому регіоні.",
                    "Тривоги немає — будильник дзвонить одразу, як звичайний.",
                    "Триває тривога — будильник чекає й будить після відбою.",
                    "«Не будити після» діє й тут: якщо відбій настане пізніше, будильник промовчить.",
                    "Немає інтернету — будильник дзвонить за розкладом, щоб ви не проспали.",
                ).forEach { line ->
                    Row {
                        Text("•", color = Night.Amber, modifier = Modifier.width(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickTime) {
        AlarmTimeDialog(
            initialMinutes = settings.scheduleMinutes,
            onPick = {
                // Змінили час — отже, хочуть, щоб будильник працював.
                settings.updateSchedule(enabled = true, minutes = it)
                pickTime = false
            },
            onDismiss = { pickTime = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTimeDialog(initialMinutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Час будильника") },
        text = { TimeInput(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
