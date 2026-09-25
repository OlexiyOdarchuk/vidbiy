package ua.vidbiy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

object Night {
    val SkyTop = Color(0xFF0A1430)
    val SkyBottom = Color(0xFF1B3A5E)
    val Glass = Color(0x14FFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val Dialog = Color(0xFF1A2A4A)

    val Amber = Color(0xFFFFC247)
    val OnAmber = Color(0xFF2A1F00)
    val Green = Color(0xFF3DD68C)
    val Red = Color(0xFFFF6B6B)
    val Blue = Color(0xFF8AB4FF)

    val Text = Color(0xFFF1F4FA)
    val TextDim = Color(0xFFA7B4CC)
}

private val scheme = darkColorScheme(
    primary = Night.Amber,
    onPrimary = Night.OnAmber,
    secondary = Night.Blue,
    onSecondary = Night.SkyTop,
    background = Night.SkyTop,
    onBackground = Night.Text,
    surface = Night.Dialog,
    onSurface = Night.Text,
    onSurfaceVariant = Night.TextDim,
    surfaceContainerLowest = Night.SkyTop,
    surfaceContainerLow = Night.Dialog,
    surfaceContainer = Night.Dialog,
    surfaceContainerHigh = Night.Dialog,
    surfaceContainerHighest = Color(0xFF26395E),
    error = Night.Red,
    outline = Color(0x52FFFFFF),
    outlineVariant = Color(0x24FFFFFF),
)

private val typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.Light),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun VidbiyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
