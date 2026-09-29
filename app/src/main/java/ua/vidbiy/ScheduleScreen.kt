package ua.vidbiy

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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

private val switchColors @Composable get() =
    SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber)

/** «1 правило», «2 правила», «5 правил». */
fun rulesCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "правил"
        n % 10 == 1 -> "правило"
        n % 10 in 2..4 -> "правила"
        else -> "правил"
    }
    return "$n $word"
}

/** Картка на головному екрані: найближчий будильник і перемикач усіх. */
@Composable
fun ScheduleCard(settings: SettingsState, onOpen: () -> Unit) {
    val next = settings.nextAlarm
    val enabledCount = settings.alarms.count { it.enabled }
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
                Text(
                    if (enabledCount > 1) "Будильники на час" else "Будильник на час",
                    style = MaterialTheme.typography.labelMedium,
                    color = Night.TextDim,
                )
                if (next != null) {
                    Text(AlarmScheduler.formatTime(next), style = MaterialTheme.typography.titleLarge)
                    Text(
                        AlarmScheduler.describeAt(next).replaceFirstChar { it.uppercase() } +
                            if (enabledCount > 1) " · увімкнено $enabledCount" else "",
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
            Switch(checked = settings.anyAlarmEnabled, onCheckedChange = settings::setAlarmsEnabled, colors = switchColors)
        }
    }
}

@Composable
fun ScheduleScreen(
    settings: SettingsState,
    permissions: Permissions,
    actions: Actions,
    editingId: Int?,
    onEdit: (Int?) -> Unit,
    onOpenRules: () -> Unit,
    onBack: () -> Unit,
) {
    val editing = settings.alarms.firstOrNull { it.id == editingId }
    if (editing != null) {
        AlarmEditor(editing, settings, onOpenRules, onBack = { onEdit(null) })
        return
    }

    val context = LocalContext.current
    val exactAllowed = remember(permissions) { AlarmScheduler.canScheduleExact(context) }
    var adding by remember { mutableStateOf(false) }

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
            Text("Будильники на час", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            settings.alarms.sortedBy { it.minutes }.forEach { alarm ->
                AlarmItem(alarm, settings, onClick = { onEdit(alarm.id) })
            }
        }
        FilledTonalButton(
            onClick = { adding = true },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Night.Glass, contentColor = Night.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Night.Amber)
            Spacer(Modifier.width(8.dp))
            Text("Додати будильник")
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

        SectionHeader("Додатково")
        GlassCard {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Rule,
                title = "Правила нічної тривоги",
                value = settings.nightRules.count { it.enabled }.let {
                    if (it == 0) "Будити пізніше, якщо вночі була тривога" else "Увімкнено ${rulesCount(it)}"
                },
                onClick = onOpenRules,
            )
        }

        SectionHeader("Як це працює")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "У заданий час програма перевіряє, чи є тривога там, де ви будете вранці.",
                    "Тривоги немає — будильник дзвонить одразу, як звичайний.",
                    "Триває тривога — будильник чекає й будить після відбою.",
                    "Якщо для будильника увімкнено правила нічної тривоги, після тривожної ночі він спрацює пізніше.",
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

    if (adding) {
        AlarmTimeDialog(
            initialMinutes = 7 * 60 + 30,
            onPick = {
                val alarm = settings.newAlarm().copy(minutes = it)
                settings.saveAlarm(alarm)
                adding = false
                onEdit(alarm.id)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AlarmItem(alarm: Alarm, settings: SettingsState, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = Night.Glass,
        border = BorderStroke(1.dp, Night.GlassBorder),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Column(Modifier.weight(1f).alpha(if (alarm.enabled) 1f else 0.5f)) {
                Text(AlarmScheduler.formatMinutes(alarm.minutes), fontSize = 40.sp, style = MaterialTheme.typography.displaySmall)
                Text(AlarmScheduler.describeDays(alarm.days), style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
                val extras = listOfNotNull(
                    alarm.place?.name,
                    "пізніше після нічної тривоги".takeIf { alarm.nightRule },
                )
                if (extras.isNotEmpty()) {
                    Text(
                        extras.joinToString(" · ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.Amber,
                    )
                }
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = { settings.saveAlarm(alarm.copy(enabled = it)) },
                colors = switchColors,
            )
        }
    }
}

@Composable
private fun AlarmEditor(alarm: Alarm, settings: SettingsState, onOpenRules: () -> Unit, onBack: () -> Unit) {
    var pickTime by remember { mutableStateOf(false) }
    var pickPlace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler { if (pickPlace) pickPlace = false else onBack() }

    if (pickPlace) {
        PlacePicker(
            current = alarm.place,
            main = settings.region,
            onPick = {
                settings.saveAlarm(alarm.copy(place = it))
                pickPlace = false
            },
            onBack = { pickPlace = false },
        )
        return
    }

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
            Text("Будильник", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = alarm.enabled,
                onCheckedChange = { settings.saveAlarm(alarm.copy(enabled = it)) },
                colors = switchColors,
            )
        }

        // Великий час: натиснути, щоб змінити
        Text(
            AlarmScheduler.formatMinutes(alarm.minutes),
            fontSize = 88.sp,
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .alpha(if (alarm.enabled) 1f else 0.5f)
                .clickable { pickTime = true },
        )
        Text(
            AlarmScheduler.nextTrigger(alarm)?.let { "Спрацює ${AlarmScheduler.describeAt(it.toInstant().toEpochMilli())}" }
                ?: "Будильник вимкнено",
            style = MaterialTheme.typography.bodyLarge,
            color = Night.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { pickTime = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Змінити час")
        }

        SectionHeader("Дні")
        DaysPicker(alarm.days) { settings.saveAlarm(alarm.copy(days = it)) }

        SectionHeader("Додатково")
        GlassCard {
            SettingsRow(
                icon = Icons.Rounded.LocationOn,
                title = "Місце",
                value = alarm.place?.let { listOfNotNull(it.name, it.detail).joinToString(", ") }
                    ?: "Основне — ${settings.region.name}",
                onClick = { pickPlace = true },
            )
            RowDivider()
            val activeRules = settings.nightRules.count { it.enabled }
            SettingsRow(
                icon = Icons.Rounded.DarkMode,
                title = "Пізніше після нічної тривоги",
                value = when {
                    activeRules == 0 -> "Спершу додайте правило"
                    else -> "Діє ${rulesCount(activeRules)}"
                },
                onClick = { if (activeRules == 0) onOpenRules() else settings.saveAlarm(alarm.copy(nightRule = !alarm.nightRule)) },
                trailing = {
                    Switch(
                        checked = alarm.nightRule,
                        onCheckedChange = { settings.saveAlarm(alarm.copy(nightRule = it)) },
                        colors = switchColors,
                    )
                },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Rule,
                title = "Правила нічної тривоги",
                value = "Спільні для всіх будильників",
                onClick = onOpenRules,
            )
        }

        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = Night.Red)
            Spacer(Modifier.width(8.dp))
            Text("Видалити будильник", color = Night.Red)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickTime) {
        AlarmTimeDialog(
            initialMinutes = alarm.minutes,
            onPick = {
                // Змінили час — отже, хочуть, щоб будильник працював.
                settings.saveAlarm(alarm.copy(minutes = it, enabled = true))
                pickTime = false
            },
            onDismiss = { pickTime = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити будильник о ${AlarmScheduler.formatMinutes(alarm.minutes)}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onBack()
                    settings.deleteAlarm(alarm.id)
                }) { Text("Видалити", color = Night.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun DaysPicker(days: Int, onChange: (Int) -> Unit) {
    GlassCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                dayLetters.forEachIndexed { i, letter ->
                    val bit = 1 shl i
                    val on = days and bit != 0
                    Surface(
                        onClick = { onChange(days xor bit) },
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
                ).forEach { (label, preset) ->
                    TextButton(onClick = { onChange(preset) }) {
                        Text(label, color = if (days == preset) Night.Amber else Night.TextDim)
                    }
                }
            }
            val presets = listOf(AlarmScheduler.WEEKDAYS, AlarmScheduler.EVERY_DAY, AlarmScheduler.WEEKEND)
            if (days !in presets) {
                Text(
                    if (days == 0) "Один раз: після спрацювання будильник вимкнеться" else AlarmScheduler.describeDays(days),
                    style = MaterialTheme.typography.bodySmall,
                    color = Night.TextDim,
                )
            }
        }
    }
}

/** Вибір місця для будильника: основне з налаштувань або будь-яке інше. */
@Composable
private fun PlacePicker(current: Region?, main: Region, onPick: (Region?) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Місце для будильника", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Де перевіряти тривогу, коли спрацює будильник. Наприклад, місто, де навчання чи робота.",
            style = MaterialTheme.typography.bodyMedium,
            color = Night.TextDim,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        GlassCard(modifier = Modifier.padding(vertical = 12.dp)) {
            SettingsRow(
                icon = Icons.Rounded.LocationOn,
                title = "Основне місце",
                value = main.name + if (current == null) " · обрано" else "",
                onClick = { onPick(null) },
            )
        }
        RegionPicker(selected = current ?: main, onPick = onPick, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmTimeDialog(initialMinutes: Int, title: String = "Час будильника", onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
