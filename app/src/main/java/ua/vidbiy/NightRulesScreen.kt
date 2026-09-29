package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

private enum class RuleDialog { FROM, TO, DURATION, ACTION, AT, LATER, DELETE }

@Composable
fun NightRulesScreen(settings: SettingsState, editingId: Int?, onEdit: (Int?) -> Unit, onBack: () -> Unit) {
    val editing = settings.nightRules.firstOrNull { it.id == editingId }
    if (editing != null) {
        RuleEditor(editing, settings, onBack = { onEdit(null) })
        return
    }
    var adding by remember { mutableStateOf(false) }
    val usedBy = settings.alarms.filter { it.nightRule }

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
            Text("Правила нічної тривоги", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Якщо вночі була тривога, будильник на час спрацює пізніше або не дзвонитиме. " +
                "Зручно для навчання чи роботи, де після нічної тривоги початок переносять.",
            style = MaterialTheme.typography.bodyMedium,
            color = Night.TextDim,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )

        if (settings.nightRules.isNotEmpty() && usedBy.isEmpty()) {
            GlassCard(color = Night.Blue.copy(alpha = 0.10f), border = Night.Blue.copy(alpha = 0.30f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = Night.Blue)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Правила діють лише для будильників, де увімкнено «Пізніше після нічної тривоги». Зараз таких немає.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            settings.nightRules.forEach { rule ->
                Surface(
                    onClick = { onEdit(rule.id) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Night.Glass,
                    border = BorderStroke(1.dp, Night.GlassBorder),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        IconBadge(Icons.Rounded.Bedtime)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            NightRules.describe(rule),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .alpha(if (rule.enabled) 1f else 0.5f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { settings.saveRule(rule.copy(enabled = it)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
                        )
                    }
                }
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
            Text("Додати правило")
        }

        SectionHeader("Як це працює")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "У час будильника програма дивиться, скільки тривало тривог у заданий проміжок ночі в місці будильника.",
                    "Якщо умова справдилась, будильник переноситься, а в новий час перевіряє тривогу як завжди.",
                    "Якщо збіглося кілька правил, діє найпізніший час, а «не будити» — важливіше за все.",
                    "Дані беруться з siren.pp.ua. Якщо їх не вдалося отримати, будильник спрацює у звичайний час.",
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
        val id = settings.newRuleId()
        ChoiceDialog(
            title = "Нове правило",
            options = NightRules.templates(id).map { it to NightRules.describe(it) },
            selected = null,
            onSelect = { picked ->
                val rule = picked ?: return@ChoiceDialog
                settings.saveRule(rule)
                adding = false
                onEdit(rule.id)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun RuleEditor(rule: NightRule, settings: SettingsState, onBack: () -> Unit) {
    var dialog by remember { mutableStateOf<RuleDialog?>(null) }
    BackHandler(onBack = onBack)
    val save: (NightRule) -> Unit = settings::saveRule

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
            Text("Правило", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = rule.enabled,
                onCheckedChange = { save(rule.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber),
            )
        }

        GlassCard(
            modifier = Modifier.padding(top = 12.dp),
            color = Night.Amber.copy(alpha = 0.10f),
            border = Night.Amber.copy(alpha = 0.35f),
        ) {
            Text(NightRules.describe(rule), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        }

        SectionHeader("Якщо вночі")
        GlassCard {
            SettingsRow(Icons.Rounded.Bedtime, "З", Prefs.formatMinutes(rule.from), onClick = { dialog = RuleDialog.FROM })
            RowDivider()
            SettingsRow(Icons.Rounded.WbSunny, "До", Prefs.formatMinutes(rule.to), onClick = { dialog = RuleDialog.TO })
            RowDivider()
            SettingsRow(
                Icons.Rounded.HourglassBottom,
                "Тривога тривала",
                durationLabel(rule.minDuration),
                onClick = { dialog = RuleDialog.DURATION },
            )
        }

        SectionHeader("Тоді")
        GlassCard {
            SettingsRow(Icons.Rounded.Bedtime, "Будильник", actionLabel(rule.action), onClick = { dialog = RuleDialog.ACTION })
            when (rule.action) {
                RuleAction.AT -> {
                    RowDivider()
                    SettingsRow(Icons.Rounded.WbSunny, "Будити о", Prefs.formatMinutes(rule.at), onClick = { dialog = RuleDialog.AT })
                }
                RuleAction.LATER -> {
                    RowDivider()
                    SettingsRow(
                        Icons.Rounded.WbSunny,
                        "На скільки пізніше",
                        NightRules.formatDuration(rule.later),
                        onClick = { dialog = RuleDialog.LATER },
                    )
                }
                RuleAction.SKIP -> Unit
            }
        }
        Text(
            if (rule.action == RuleAction.AT) {
                "Якщо цей час не пізніший за звичайний час будильника, правило не діє."
            } else {
                "Вікно рахується до часу будильника, тож тривога після нього не враховується."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Night.TextDim,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )

        TextButton(
            onClick = { dialog = RuleDialog.DELETE },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = Night.Red)
            Spacer(Modifier.width(8.dp))
            Text("Видалити правило", color = Night.Red)
        }
        Spacer(Modifier.height(24.dp))
    }

    val close = { dialog = null }
    when (dialog) {
        RuleDialog.FROM -> AlarmTimeDialog(rule.from, "Початок проміжку", onPick = { save(rule.copy(from = it)); close() }, onDismiss = close)
        RuleDialog.TO -> AlarmTimeDialog(rule.to, "Кінець проміжку", onPick = { save(rule.copy(to = it)); close() }, onDismiss = close)
        RuleDialog.AT -> AlarmTimeDialog(rule.at, "Будити о", onPick = { save(rule.copy(at = it)); close() }, onDismiss = close)
        RuleDialog.DURATION -> ChoiceDialog(
            title = "Тривога тривала",
            options = NightRules.durations.map { it to durationLabel(it) },
            selected = rule.minDuration,
            onSelect = { save(rule.copy(minDuration = it)); close() },
            onDismiss = close,
        )
        RuleDialog.ACTION -> ChoiceDialog(
            title = "Будильник",
            options = RuleAction.entries.map { it to actionLabel(it) },
            selected = rule.action,
            onSelect = { save(rule.copy(action = it)); close() },
            onDismiss = close,
        )
        RuleDialog.LATER -> ChoiceDialog(
            title = "На скільки пізніше",
            options = NightRules.laterOptions.map { it to NightRules.formatDuration(it) },
            selected = rule.later,
            onSelect = { save(rule.copy(later = it)); close() },
            onDismiss = close,
        )
        RuleDialog.DELETE -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Видалити правило?") },
            text = { Text(NightRules.describe(rule)) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    onBack()
                    settings.deleteRule(rule.id)
                }) { Text("Видалити", color = Night.Red) }
            },
            dismissButton = { TextButton(onClick = close) { Text("Скасувати") } },
        )
        null -> Unit
    }
}

private fun durationLabel(minutes: Int) =
    if (minutes == 0) "Будь-скільки" else "Сумарно щонайменше ${NightRules.formatDuration(minutes)}"

private fun actionLabel(action: RuleAction) = when (action) {
    RuleAction.AT -> "Будити о визначеній порі"
    RuleAction.LATER -> "Будити пізніше"
    RuleAction.SKIP -> "Не будити"
}
