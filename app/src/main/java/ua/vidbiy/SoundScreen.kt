package ua.vidbiy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SoundScreen(settings: SettingsState, actions: Actions, onBack: () -> Unit) {
    val context = LocalContext.current
    val preview = remember { SoundPreview(context) }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }

    fun toggle(id: String) {
        if (playing == id) {
            preview.stop()
            playing = null
            return
        }
        val uri = Sounds.uriFor(context, id, settings.prefs) ?: return
        preview.play(id, uri) { playing = null }
        playing = preview.playingId
    }

    fun choose(id: String) {
        settings.updateSound(id)
        toggle(id)
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
            Text("Звук будильника", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Натисніть, щоб послухати й обрати.",
            style = MaterialTheme.typography.bodyMedium,
            color = Night.TextDim,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )

        SectionHeader("Системний")
        GlassCard {
            SettingsRow(
                icon = if (playing == Sounds.ALARM_ID) Icons.Rounded.Stop else Icons.Rounded.Alarm,
                title = "Системний будильник",
                value = "Та сама мелодія, що в годиннику телефона",
                onClick = { choose(Sounds.ALARM_ID) },
                trailing = { SelectedMark(settings.soundId == Sounds.ALARM_ID) },
            )
        }

        SectionHeader("Вбудовані")
        GlassCard {
            Sounds.builtIn.forEachIndexed { i, sound ->
                if (i > 0) RowDivider()
                SettingsRow(
                    icon = if (playing == sound.id) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    title = sound.name,
                    value = sound.desc,
                    onClick = { choose(sound.id) },
                    trailing = { SelectedMark(settings.soundId == sound.id) },
                )
            }
        }

        SectionHeader("Інші")
        GlassCard {
            val systemName = settings.prefs.systemSoundName.takeIf { settings.prefs.systemSoundUri != null }
            SettingsRow(
                icon = if (playing == Sounds.SYSTEM_ID) Icons.Rounded.Stop else Icons.Rounded.LibraryMusic,
                title = "Мелодія системи",
                value = systemName ?: "Обрати з мелодій телефона",
                onClick = { if (systemName != null) choose(Sounds.SYSTEM_ID) else actions.pickSystemSound() },
                trailing = {
                    if (systemName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = actions.pickSystemSound) { Text("Змінити") }
                            SelectedMark(settings.soundId == Sounds.SYSTEM_ID)
                        }
                    }
                },
            )
            RowDivider()
            val customName = settings.prefs.customSoundName.takeIf { settings.prefs.customSoundUri != null }
            SettingsRow(
                icon = if (playing == Sounds.CUSTOM_ID) Icons.Rounded.Stop else Icons.Rounded.AudioFile,
                title = "Свій файл",
                value = customName ?: "MP3, M4A, OGG чи інший аудіофайл",
                onClick = { if (customName != null) choose(Sounds.CUSTOM_ID) else actions.pickCustomSound() },
                trailing = {
                    if (customName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = actions.pickCustomSound) { Text("Змінити") }
                            SelectedMark(settings.soundId == Sounds.CUSTOM_ID)
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Обрано", tint = Night.Amber)
    else Spacer(Modifier.width(24.dp))
}
