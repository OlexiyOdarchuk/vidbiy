package ua.vidbiy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

object Night {
    val SkyTop = Color(0xFFFF00A8)
    val SkyBottom = Color(0xFF00E5FF)
    val Glass = Color(0xFFFFFF00)
    val GlassBorder = Color(0xFF7A00FF)
    val Dialog = Color(0xFFFF7A00)

    val Amber = Color(0xFFFF0055)
    val OnAmber = Color(0xFFFFFFFF)
    val Green = Color(0xFF00A83B)
    val Red = Color(0xFFFF0033)
    val Blue = Color(0xFF1200A8)

    val Text = Color(0xFF16002B)
    val TextDim = Color(0xFF3B075F)
}

private val scheme = lightColorScheme(
    primary = Night.Amber,
    onPrimary = Night.OnAmber,
    secondary = Night.Blue,
    onSecondary = Color.White,
    background = Night.SkyTop,
    onBackground = Night.Text,
    surface = Night.Dialog,
    onSurface = Night.Text,
    onSurfaceVariant = Night.TextDim,
    surfaceContainerLowest = Night.SkyTop,
    surfaceContainerLow = Night.Dialog,
    surfaceContainer = Night.Dialog,
    surfaceContainerHigh = Night.Dialog,
    surfaceContainerHighest = Color(0xFFFFB000),
    error = Night.Red,
    outline = Night.GlassBorder,
    outlineVariant = Color(0xFF9CB3C6),
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
