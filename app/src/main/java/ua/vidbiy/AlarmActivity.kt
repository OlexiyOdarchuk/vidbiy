package ua.vidbiy

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmActivity : ComponentActivity() {
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

        setContent {
            VidbiyTheme {
                val state by WatchRepo.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.phase) {
                    if (state.phase != Phase.RINGING) finish()
                }
                AlarmScreen(
                    reason = state.reason,
                    onDismiss = {
                        WatchService.send(this, WatchService.ACTION_STOP)
                        finish()
                    },
                    onSnooze = {
                        WatchService.send(this, WatchService.ACTION_SNOOZE)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun AlarmScreen(reason: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val time by produceState(LocalTime.now()) {
        while (true) {
            delay(1_000)
            value = LocalTime.now()
        }
    }

    NightBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                time.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp),
                modifier = Modifier.padding(top = 32.dp),
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusOrb(Phase.RINGING, size = 240.dp)
                Spacer(Modifier.height(24.dp))
                Text("Прокидайтеся!", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.titleMedium,
                    color = Night.TextDim,
                    textAlign = TextAlign.Center,
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onDismiss,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Night.Amber, contentColor = Night.OnAmber),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) {
                    Text("Вимкнути", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSnooze, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Rounded.Snooze, contentDescription = null, tint = Night.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("Ще 5 хвилин", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                }
            }
        }
    }
}
