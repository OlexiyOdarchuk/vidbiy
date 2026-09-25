package ua.vidbiy

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NightBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night.SkyTop, Night.SkyBottom))),
    ) {
        CompositionLocalProvider(LocalContentColor provides Night.Text) { content() }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    color: Color = Night.Glass,
    border: Color = Night.GlassBorder,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = color,
        border = BorderStroke(1.dp, border),
    ) {
        Column(content = content)
    }
}

private data class OrbStyle(val icon: ImageVector, val color: Color, val pulseMs: Int?)

private fun orbStyle(phase: Phase) = when (phase) {
    Phase.IDLE -> OrbStyle(Icons.Rounded.Bedtime, Night.Blue, null)
    Phase.WAITING_ALERT -> OrbStyle(Icons.Rounded.Radar, Night.Blue, 2800)
    Phase.ALERT -> OrbStyle(Icons.Rounded.Campaign, Night.Red, 1800)
    Phase.RINGING -> OrbStyle(Icons.Rounded.Alarm, Night.Amber, 1100)
    Phase.SNOOZED -> OrbStyle(Icons.Rounded.Snooze, Night.Amber, 2800)
}

@Composable
fun StatusOrb(phase: Phase, size: Dp = 208.dp, onClick: (() -> Unit)? = null, clickLabel: String? = null) {
    val style = orbStyle(phase)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "orbPress")
    val color by animateColorAsState(style.color, tween(600), label = "orbColor")
    val transition = rememberInfiniteTransition(label = "orbPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(style.pulseMs ?: 2800, easing = LinearEasing)),
        label = "orbProgress",
    )

    val clickable = if (onClick != null) {
        Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
            )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(clickable),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val maxR = this.size.minDimension / 2
            if (style.pulseMs != null) {
                for (i in 0..2) {
                    val f = (progress + i / 3f) % 1f
                    drawCircle(color.copy(alpha = 0.28f * (1f - f)), radius = maxR * (0.56f + 0.44f * f))
                }
            } else {
                drawCircle(color.copy(alpha = 0.06f), radius = maxR * 0.86f)
                drawCircle(color.copy(alpha = 0.04f), radius = maxR)
            }
        }
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.10f))))
                .border(1.5.dp, color.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(style.icon, label = "orbIcon") { icon ->
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.24f))
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Night.TextDim,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = Night.Amber) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Night.TextDim,
            )
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = Night.GlassBorder, modifier = Modifier.padding(start = 70.dp))
}

@Composable
fun <T> ChoiceDialog(
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
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(options) { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutoffDialog(initialMinutes: Int, onPick: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val start = if (initialMinutes >= 0) initialMinutes else 14 * 60
    val state = rememberTimePickerState(start / 60, start % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не будити після") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Якщо відбій настане пізніше, будильник вимкнеться без сигналу. Наприклад, коли пари вже закінчились.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Night.TextDim,
                )
                TimeInput(state = state, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Без обмеження") } },
    )
}
