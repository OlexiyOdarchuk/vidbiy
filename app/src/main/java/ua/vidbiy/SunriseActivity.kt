package ua.vidbiy

import android.app.NotificationManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.graphics.Color as UiColor

/** Тихий світанок перед будильником на час: екран поволі світлішає від темного до теплого. */
class SunriseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SUNRISE)

        val at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()
        val total = (at - startedAt).coerceAtLeast(60_000L)

        setContent {
            VidbiyTheme {
                val state by WatchRepo.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.phase) {
                    if (state.phase == Phase.RINGING) finish()
                }
                val progress by produceState(0f) {
                    while (true) {
                        val now = System.currentTimeMillis()
                        if (now > at + CLOSE_AFTER_MS) finish()
                        value = ((now - startedAt).toFloat() / total).coerceIn(0f, 1f)
                        delay(1_000)
                    }
                }
                LaunchedEffect(progress) {
                    // Яскравість самого екрана теж росте, інакше навіть світлий фон уночі ледь помітний.
                    window.attributes = window.attributes.apply { screenBrightness = 0.02f + 0.98f * progress * progress }
                }
                SunriseScreen(progress, AlarmScheduler.formatTime(at), onDismiss = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_AT = "at"
        private const val CLOSE_AFTER_MS = 2 * 60_000L
    }
}

private val dawn = listOf(
    UiColor(0xFF05060A),
    UiColor(0xFF2A1638),
    UiColor(0xFF8E3B3B),
    UiColor(0xFFE0803A),
    UiColor(0xFFFFD08A),
)

private fun dawnColor(t: Float): UiColor {
    val x = t.coerceIn(0f, 1f) * (dawn.size - 1)
    val i = x.toInt().coerceAtMost(dawn.size - 2)
    return lerp(dawn[i], dawn[i + 1], x - i)
}

@Composable
private fun SunriseScreen(progress: Float, alarmTime: String, onDismiss: () -> Unit) {
    val time by produceState(LocalTime.now()) {
        while (true) {
            delay(1_000)
            value = LocalTime.now()
        }
    }
    val sky = dawnColor(progress * 0.8f)
    val horizon = dawnColor(progress)
    val text = if (progress > 0.7f) UiColor(0xFF2A1F00) else UiColor(0xFFF1F4FA)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(sky, horizon)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
                Text(
                    time.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp),
                    color = text,
                )
                Text("Будильник о $alarmTime", style = MaterialTheme.typography.titleMedium, color = text.copy(alpha = 0.75f))
            }
            TextButton(onClick = onDismiss) {
                Text("Я вже прокинувся", style = MaterialTheme.typography.titleMedium, color = text)
            }
        }
    }
}
