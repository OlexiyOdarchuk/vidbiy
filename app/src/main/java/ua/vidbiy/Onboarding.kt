package ua.vidbiy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val STEPS = 4

/** Перший запуск: привітання → дозволи → регіон → короткий тур. */
@Composable
fun Onboarding(settings: SettingsState, permissions: Permissions, actions: Actions, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }

    NightBackground {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            StepIndicator(step)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * dir / 4 } + fadeOut())
                },
                label = "onboardingStep",
                modifier = Modifier.weight(1f),
            ) { s ->
                when (s) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> PermissionsStep(permissions, actions, onNext = { step = 2 })
                    2 -> RegionStep(settings, onNext = { step = 3 })
                    else -> TourStep(onDone)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        repeat(STEPS) { i ->
            val color by animateColorAsState(if (i <= step) Night.Amber else Night.GlassBorder, label = "stepColor")
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Night.Amber, contentColor = Night.OnAmber),
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 24.dp, bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Night.TextDim)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        StatusOrb(Phase.IDLE, size = 240.dp)
        Spacer(Modifier.height(32.dp))
        Text("Відбій", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Тривога застала під час сну? Спіть далі. Будильник розбудить вас одразу після відбою.",
            style = MaterialTheme.typography.bodyLarge,
            color = Night.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Почати", onNext)
    }
}

@Composable
private fun PermissionsStep(permissions: Permissions, actions: Actions, onNext: () -> Unit) {
    val allGranted = permissions.notifications && permissions.fullScreen && permissions.battery
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StepTitle(
                "Кілька дозволів",
                "Щоб будильник спрацював навіть на заблокованому телефоні, поки ви спите.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionCard(
                    Icons.Rounded.Notifications,
                    "Сповіщення",
                    "Без них будильник не зможе ні з'явитися, ні задзвонити.",
                    permissions.notifications,
                    actions.requestNotifications,
                )
                PermissionCard(
                    Icons.Rounded.Fullscreen,
                    "Показ на весь екран",
                    "Будильник відкриється поверх екрана блокування.",
                    permissions.fullScreen,
                    actions.openFullScreenSettings,
                )
                PermissionCard(
                    Icons.Rounded.BatteryChargingFull,
                    "Робота без обмежень батареї",
                    "Щоб телефон не приспав програму, поки вона чекає на відбій.",
                    permissions.battery,
                    actions.openBatterySettings,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!allGranted) {
            Text(
                "Дозволи можна надати й пізніше в налаштуваннях.",
                style = MaterialTheme.typography.bodySmall,
                color = Night.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        PrimaryButton("Далі", onNext)
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, text: String, granted: Boolean, onGrant: () -> Unit) {
    GlassCard(
        color = if (granted) Night.Green.copy(alpha = 0.08f) else Night.Glass,
        border = if (granted) Night.Green.copy(alpha = 0.35f) else Night.GlassBorder,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            IconBadge(icon, tint = if (granted) Night.Green else Night.Amber)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Надано", tint = Night.Green)
            } else {
                TextButton(onClick = onGrant) { Text("Дозволити") }
            }
        }
    }
}

@Composable
private fun RegionStep(settings: SettingsState, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepTitle(
            "Де ви навчаєтесь чи працюєте?",
            "Будильник стежитиме за тривогою саме тут. Можна обрати область, район або громаду.",
        )
        RegionPicker(
            selected = settings.region,
            onPick = {
                settings.updateRegion(it)
                onNext()
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Далі: ${settings.region.name}", onNext)
    }
}

private class TourPage(val title: String, val text: String, val visual: @Composable ColumnScope.() -> Unit)

@Composable
private fun TourStep(onDone: () -> Unit) {
    val pages = listOf(
        TourPage(
            "Торкніться місяця",
            "Тривога застала під час сну? Торкніться місяця на головному екрані й спокійно спіть далі.",
        ) {
            StatusOrb(Phase.IDLE, size = 200.dp)
            Spacer(Modifier.height(12.dp))
            TouchHint("Торкніться, щоб увімкнути")
        },
        TourPage(
            "Програма стежить за тривогою",
            "Кожні 20 секунд вона перевіряє стан тривоги. Щойно настане відбій, пролунає гучний будильник, навіть у беззвучному режимі.",
        ) {
            StatusOrb(Phase.ALERT, size = 200.dp)
        },
        TourPage(
            "Або звичайний будильник",
            "Поставте час, наприклад 07:30. Якщо тоді тривоги немає, він задзвонить як звичайний, а якщо триває — розбудить після відбою.",
        ) {
            DemoTile(Icons.Rounded.Alarm, "Будильник на час", "07:30 · Будні", Modifier.widthIn(min = 200.dp))
        },
        TourPage(
            "Налаштуйте під себе",
            "«Не будити після»: якщо відбій настане, коли пари вже закінчились, будильник не задзвонить. Регіон і джерела даних змінюються в налаштуваннях.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 360.dp)) {
                DemoTile(Icons.Rounded.Schedule, "Не будити після", "14:30", Modifier.weight(1f))
                DemoTile(Icons.Rounded.WifiOff, "Якщо зник зв'язок", "Будити", Modifier.weight(1f))
            }
        },
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val page = pages[i]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.height(260.dp),
                    content = page.visual,
                )
                Spacer(Modifier.height(24.dp))
                Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    page.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Night.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 340.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        ) {
            repeat(pages.size) { i ->
                val width by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "dot")
                val color by animateColorAsState(
                    if (i == pager.currentPage) Night.Amber else Night.GlassBorder,
                    label = "dotColor",
                )
                Box(Modifier.size(width = width, height = 8.dp).clip(CircleShape).background(color))
            }
        }
        PrimaryButton(if (last) "Почати користуватися" else "Далі") {
            if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}

@Composable
fun TouchHint(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(Night.Amber.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Night.Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Night.Amber)
    }
}

@Composable
private fun DemoTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = Night.Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Night.TextDim)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
