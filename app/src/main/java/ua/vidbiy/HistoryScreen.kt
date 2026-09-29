package ua.vidbiy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("uk"))

private fun time(ms: Long) = AlarmScheduler.formatTime(ms)

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { History.refresh(context) }
    val sessions by History.sessions.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Історія", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (sessions.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Очистити") }
        }

        if (sessions.isEmpty()) {
            Text(
                "Тут з'явиться, коли вмикалося очікування, коли були тривога й відбій і чому дзвонив будильник.",
                style = MaterialTheme.typography.bodyLarge,
                color = Night.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(sessions, key = { it.startedAt }) { s ->
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val day = dayFormat.format(Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()))
                        Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (s.endedAt == null) "з ${time(s.startedAt)} · триває" else "${time(s.startedAt)}–${time(s.endedAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Night.TextDim,
                        )
                        s.events.forEach { e ->
                            Row {
                                Text(time(e.at), style = MaterialTheme.typography.bodyMedium, color = Night.Amber, modifier = Modifier.width(56.dp))
                                Text(e.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(12.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистити історію?") },
            confirmButton = {
                TextButton(onClick = {
                    History.clear(context)
                    confirmClear = false
                }) { Text("Очистити", color = Night.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Скасувати") } },
        )
    }
}
