package ua.vidbiy

import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.sqrt
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

        val prefs = Prefs(this)
        setContent {
            VidbiyTheme {
                val state by WatchRepo.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.phase) {
                    if (state.phase != Phase.RINGING) finish()
                }
                var task by remember { mutableStateOf<DismissTask?>(null) }
                val dismiss = {
                    WatchService.send(this, WatchService.ACTION_STOP)
                    finish()
                }
                BackHandler(enabled = task != null) { task = null }
                when (task) {
                    DismissTask.MATH -> MathTaskScreen(prefs.mathLevel, prefs.mathCount, onSolved = dismiss, onBack = { task = null })
                    DismissTask.SHAKE -> ShakeTaskScreen(
                        prefs.shakeCount,
                        onSolved = dismiss,
                        onNoSensor = { task = DismissTask.MATH },
                        onBack = { task = null },
                    )
                    else -> AlarmScreen(
                        reason = state.reason,
                        onDismiss = {
                            val needed = prefs.dismissTask
                            if (needed == DismissTask.NONE) dismiss() else task = needed
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

@Composable
private fun TaskScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    NightBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
                content = content,
            )
            TextButton(onClick = onBack) { Text("Назад", color = Night.TextDim) }
        }
    }
}

@Composable
private fun MathTaskScreen(level: MathLevel, count: Int, onSolved: () -> Unit, onBack: () -> Unit) {
    var solved by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(MathTasks.generate(level)) }
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val check = {
        if (answer.trim().toIntOrNull() == problem.answer) {
            solved++
            wrong = false
            if (solved >= count) onSolved() else problem = MathTasks.generate(level)
        } else {
            wrong = true
            problem = MathTasks.generate(level)
        }
        answer = ""
    }

    TaskScaffold(if (count > 1) "Приклад ${solved + 1} з $count" else "Розв'яжіть приклад", onBack) {
        Text("${problem.text} =", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { v -> answer = v.filter { it.isDigit() }.take(5) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { check() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(200.dp)
                .focusRequester(focus),
        )
        Text(
            if (wrong) "Неправильно, ось інший приклад" else " ",
            color = Night.Red,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = check,
            enabled = answer.isNotEmpty(),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Night.Amber, contentColor = Night.OnAmber),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Перевірити", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ShakeTaskScreen(target: Int, onSolved: () -> Unit, onNoSensor: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var shakes by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            onNoSensor()
            return@DisposableEffect onDispose { }
        }
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                // Поріг і пауза між струсами, щоб легкий рух чи вібрація самого телефона не рахувались.
                if (g > SHAKE_G && now - last > SHAKE_GAP_MS) {
                    last = now
                    shakes++
                    if (shakes >= target) onSolved()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    TaskScaffold("Струсніть телефон", onBack) {
        Text("${shakes.coerceAtMost(target)} / $target", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { shakes.toFloat() / target },
            color = Night.Amber,
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

private const val SHAKE_G = 2.2f
private const val SHAKE_GAP_MS = 250L
