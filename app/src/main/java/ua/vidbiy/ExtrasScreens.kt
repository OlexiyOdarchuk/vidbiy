package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sinceFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk"))

private val switchColors @Composable get() =
    SwitchDefaults.colors(checkedTrackColor = Night.Amber, checkedThumbColor = Night.OnAmber)

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ScrollScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Night.TextDim,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

// ---------- Вимкнення будильника ----------

fun dismissSummary(settings: SettingsState) = when (settings.dismissTask) {
    DismissTask.NONE -> DismissTask.NONE.title
    DismissTask.MATH -> "Приклад · ${settings.mathLevel.title.lowercase()}" +
        if (settings.mathCount > 1) " · ${settings.mathCount} шт." else ""
    DismissTask.SHAKE -> "Струснути ${settings.shakeCount} разів"
}

@Composable
fun DismissScreen(settings: SettingsState, actions: Actions, onBack: () -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    ScrollScreen {
        ScreenHeader("Вимкнення будильника", onBack)
        Note("Щоб не вимкнути будильник крізь сон, для кнопки «Вимкнути» можна додати завдання. «Ще 5 хвилин» працює без завдання.")
        GlassCard {
            DismissTask.entries.forEachIndexed { i, task ->
                if (i > 0) RowDivider()
                SettingsRow(
                    icon = when (task) {
                        DismissTask.NONE -> Icons.Rounded.Tune
                        DismissTask.MATH -> Icons.Rounded.Calculate
                        DismissTask.SHAKE -> Icons.Rounded.Vibration
                    },
                    title = task.title,
                    onClick = { settings.updateDismiss(task = task) },
                    trailing = {
                        androidx.compose.material3.RadioButton(
                            selected = settings.dismissTask == task,
                            onClick = { settings.updateDismiss(task = task) },
                        )
                    },
                )
            }
        }
        when (settings.dismissTask) {
            DismissTask.MATH -> {
                SectionHeader("Приклад")
                GlassCard {
                    SettingsRow(
                        Icons.Rounded.Calculate,
                        "Складність",
                        "${settings.mathLevel.title}, наприклад ${settings.mathLevel.sample}",
                        onClick = { dialog = "level" },
                    )
                    RowDivider()
                    SettingsRow(Icons.Rounded.Numbers, "Скільки прикладів", "${settings.mathCount}", onClick = { dialog = "count" })
                }
            }
            DismissTask.SHAKE -> {
                SectionHeader("Струшування")
                GlassCard {
                    SettingsRow(Icons.Rounded.Numbers, "Скільки разів", "${settings.shakeCount}", onClick = { dialog = "shakes" })
                }
                Note("Якщо в телефоні немає датчика руху, замість струшування буде приклад.")
            }
            DismissTask.NONE -> Unit
        }
        if (settings.dismissTask != DismissTask.NONE) {
            TextButton(onClick = actions.test, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) {
                Text("Спробувати: перевірити звук")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    val close = { dialog = null }
    when (dialog) {
        "level" -> ChoiceDialog(
            title = "Складність",
            options = MathLevel.entries.map { it to "${it.title}: ${it.sample}" },
            selected = settings.mathLevel,
            onSelect = { settings.updateDismiss(level = it); close() },
            onDismiss = close,
        )
        "count" -> ChoiceDialog(
            title = "Скільки прикладів",
            options = (1..5).map { it to "$it" },
            selected = settings.mathCount,
            onSelect = { settings.updateDismiss(count = it); close() },
            onDismiss = close,
        )
        "shakes" -> ChoiceDialog(
            title = "Скільки разів струснути",
            options = listOf(10, 20, 30, 50).map { it to "$it" },
            selected = settings.shakeCount,
            onSelect = { settings.updateDismiss(shakes = it); close() },
            onDismiss = close,
        )
    }
}

// ---------- Вебхуки ----------

@Composable
fun WebhooksScreen(settings: SettingsState, editingId: Int?, onEdit: (Int?) -> Unit, onBack: () -> Unit) {
    val editing = settings.webhooks.firstOrNull { it.id == editingId }
    if (editing != null) {
        WebhookEditor(editing, settings, onBack = { onEdit(null) })
        return
    }
    ScrollScreen {
        ScreenHeader("Розумний дім і вебхуки", onBack)
        Note(
            "Коли стається подія — наприклад, відбій або сигнал будильника, — програма надсилає запит на вказану адресу. " +
                "Так можна ввімкнути світло чи чайник через Home Assistant або повідомити власний сервер."
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            settings.webhooks.forEach { hook ->
                Surface(
                    onClick = { onEdit(hook.id) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Night.Glass,
                    border = BorderStroke(1.dp, Night.GlassBorder),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        IconBadge(Icons.Rounded.Webhook)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f).alpha(if (hook.enabled) 1f else 0.5f)) {
                            Text(hook.name.ifBlank { "Без назви" }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                HookEvent.entries.filter { it.id in hook.events }.joinToString(", ") { it.title.lowercase() }
                                    .replaceFirstChar { it.uppercase() }.ifEmpty { "Жодної події" },
                                style = MaterialTheme.typography.bodySmall,
                                color = Night.TextDim,
                            )
                        }
                        Switch(checked = hook.enabled, onCheckedChange = { settings.saveWebhook(hook.copy(enabled = it)) }, colors = switchColors)
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = {
                val hook = settings.newWebhook()
                settings.saveWebhook(hook)
                onEdit(hook.id)
            },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Night.Glass, contentColor = Night.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Night.Amber)
            Spacer(Modifier.width(8.dp))
            Text("Додати вебхук")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WebhookEditor(hook: Webhook, settings: SettingsState, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var name by remember(hook.id) { mutableStateOf(hook.name) }
    var url by remember(hook.id) { mutableStateOf(hook.url) }
    var headers by remember(hook.id) { mutableStateOf(hook.headers) }
    var body by remember(hook.id) { mutableStateOf(hook.body) }
    var pickMethod by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var result by remember(hook.id) { mutableStateOf(settings.prefs.webhookResult(hook.id)) }
    val current = hook.copy(name = name, url = url, headers = headers, body = body)
    val save = { settings.saveWebhook(current) }

    ScrollScreen {
        ScreenHeader("Вебхук", onBack = {
            save()
            onBack()
        }) {
            Switch(checked = hook.enabled, onCheckedChange = { settings.saveWebhook(current.copy(enabled = it)) }, colors = switchColors)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            Field("Назва", name, "Наприклад, «Світло в спальні»") { name = it; settings.saveWebhook(current.copy(name = it)) }
            Field("Адреса", url, "http://homeassistant.local:8123/api/webhook/vidbiy") {
                url = it
                settings.saveWebhook(current.copy(url = it))
            }
        }

        SectionHeader("Запит")
        GlassCard {
            SettingsRow(Icons.AutoMirrored.Rounded.Send, "Метод", hook.method, onClick = { pickMethod = true })
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            Field("Заголовки", headers, "Authorization: Bearer …", singleLine = false) {
                headers = it
                settings.saveWebhook(current.copy(headers = it))
            }
            if (hook.method != "GET") {
                Field("Тіло запиту", body, Webhooks.DEFAULT_BODY, singleLine = false, mono = true) {
                    body = it
                    settings.saveWebhook(current.copy(body = it))
                }
            }
        }
        Note(
            "Підстановки в адресі, заголовках і тілі: ${Webhooks.PLACEHOLDERS}. Заголовки — «Ключ: значення», кожен з нового рядка. " +
                "Порожнє тіло — стандартний JSON."
        )

        SectionHeader("Коли надсилати")
        GlassCard {
            HookEvent.entries.filter { it != HookEvent.TEST }.forEach { event ->
                val on = event.id in hook.events
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val events = if (on) hook.events - event.id else hook.events + event.id
                            settings.saveWebhook(current.copy(events = events))
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Checkbox(
                        checked = on,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Night.Amber, checkmarkColor = Night.OnAmber),
                        modifier = Modifier.padding(12.dp),
                    )
                    Text(event.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        FilledTonalButton(
            onClick = {
                save()
                sending = true
                Webhooks.test(context, current, settings.region.name) { r ->
                    result = r
                    sending = false
                }
            },
            enabled = url.isNotBlank() && !sending,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Night.Glass, contentColor = Night.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(52.dp),
        ) {
            Text(if (sending) "Надсилання…" else "Надіслати перевірку")
        }
        result?.let {
            Text(
                "Останній результат: $it",
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Надіслано")) Night.Green else Night.Red,
                modifier = Modifier.padding(8.dp),
            )
        }

        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = Night.Red)
            Spacer(Modifier.width(8.dp))
            Text("Видалити вебхук", color = Night.Red)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickMethod) {
        ChoiceDialog(
            title = "Метод",
            options = Webhooks.methods.map { it to it },
            selected = hook.method,
            onSelect = {
                settings.saveWebhook(current.copy(method = it))
                pickMethod = false
            },
            onDismiss = { pickMethod = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити вебхук?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onBack()
                    settings.deleteWebhook(hook.id)
                }) { Text("Видалити", color = Night.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    mono: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Night.TextDim.copy(alpha = 0.6f)) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------- Статистика ----------

@Composable
fun StatsScreen(settings: SettingsState, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { History.refresh(context) }
    val sessions by History.sessions.collectAsStateWithLifecycle()
    val personal = remember(sessions) { Stats.personal(sessions) }
    val place by produceState<Result<PlaceStats?>?>(null, settings.region) {
        value = withContext(Dispatchers.IO) {
            NightRules.history(settings.region)?.let { Result.success(Stats.place(it)) } ?: Result.failure(Exception())
        }
    }

    ScrollScreen {
        ScreenHeader("Статистика", onBack)

        SectionHeader("Ваші ночі за 30 днів")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatLine("Ночей з очікуванням", "${personal.sessions}")
                StatLine("З них із тривогою", "${personal.nightsWithAlert}")
                StatLine("Сигналів будильника", "${personal.rings}")
                StatLine("Відкладань «ще 5 хвилин»", "${personal.snoozes}")
                StatLine("Перенесень через нічну тривогу", "${personal.shifted}")
                if (personal.sessions == 0 && sessions.isNotEmpty()) {
                    Text(
                        "Записи до версії 1.5 не враховуються: рахунок почнеться з наступних ночей.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim,
                    )
                }
                personal.ringToStopMin?.let { StatLine("Від сигналу до вимкнення", if (it == 0) "менше хвилини" else "у середньому $it хв") }
            }
        }

        SectionHeader("Тривоги: ${settings.region.name}")
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val r = place) {
                    null -> Text("Завантаження…", color = Night.TextDim)
                    else -> {
                        val s = r.getOrNull()
                        when {
                            r.isFailure -> Text("Не вдалося завантажити дані", color = Night.TextDim)
                            s == null -> Text("Останнім часом тривог не було", color = Night.TextDim)
                            else -> {
                                val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
                                val since = sinceFormat.format(Instant.ofEpochMilli(s.since).atZone(ZoneId.systemDefault()))
                                if (s.since <= weekAgo) StatLine("За останні 7 днів", "${s.count7d}") else StatLine("З $since", "${s.count}")
                                StatLine("Середня тривалість", NightRules.formatDuration(s.avgMin))
                                StatLine("Найдовша", NightRules.formatDuration(s.longestMin))
                                StatLine("Почалися вночі (00–06)", "${s.nightShare} %")
                                s.nightEndHour?.let { StatLine("Нічні частіше закінчуються", "о ${it}-й годині") }
                                Spacer(Modifier.height(8.dp))
                                Text("Тривалість тривог по днях", style = MaterialTheme.typography.labelMedium, color = Night.TextDim)
                                DayBars(s.perDay)
                                Text(
                                    "За даними siren.pp.ua: останні ${s.count} тривог, з $since. Раніші дні невідомі.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Night.TextDim,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Стовпчики: хвилини тривоги за кожен із останніх днів; підписи — числа місяця. */
@Composable
private fun DayBars(minutes: List<Int>) {
    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(60)
    val unknown = Night.Text.copy(alpha = 0.05f)
    val today = LocalDate.now()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(top = 8.dp)
    ) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (minutes.size - 1)) / minutes.size
        minutes.forEachIndexed { i, m ->
            val h = when {
                m < 0 -> size.height
                m == 0 -> 2.dp.toPx()
                else -> (size.height * m / max).coerceAtLeast(3.dp.toPx())
            }
            drawRoundRect(
                color = when {
                    m < 0 -> unknown
                    m == 0 -> Night.GlassBorder
                    else -> Night.Amber
                },
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        minutes.indices.forEach { i ->
            val day = today.minusDays((minutes.size - 1 - i).toLong()).dayOfMonth
            Text(
                if (i % 2 == (minutes.size - 1) % 2) "$day" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Night.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
