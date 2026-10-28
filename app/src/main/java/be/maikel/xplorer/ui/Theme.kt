package be.maikel.xplorer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import be.maikel.xplorer.R

object Xp {
    val Abyss = Color(0xFF050912)
    val Night = Color(0xFF0B1426)
    val Night2 = Color(0xFF121E36)
    val Edge = Color(0x1F7FC8EE)
    val Edge2 = Color(0x337FC8EE)
    val Ice = Color(0xFFE3ECF8)
    val Slate = Color(0xFF7F8EA9)
    val Cyan = Color(0xFF3FD8F2)
    val CyanDeep = Color(0xFF1D8FC4)
    val Gold = Color(0xFFE8BE6E)
    val Gain = Color(0xFF3EE39A)
    val Loss = Color(0xFFFF5F76)
    val Amber = Color(0xFFF4B44C)
}

// Polices variables : la graisse est appliquée par les réglages de variation par défaut de Font().
private fun variable(res: Int, w: Int) = Font(res, FontWeight(w))

val Display = FontFamily(variable(R.font.space_grotesk, 400), variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 600), variable(R.font.space_grotesk, 700))

val Body = FontFamily(variable(R.font.manrope, 400), variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700))

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, letterSpacing = (-1.6).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
)

@Composable
fun XplorerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Xp.Cyan, onPrimary = Color(0xFF02161D), secondary = Xp.Gold, onSecondary = Color(0xFF231700),
            background = Xp.Abyss, onBackground = Xp.Ice, surface = Xp.Night, onSurface = Xp.Ice,
            surfaceVariant = Xp.Night2, onSurfaceVariant = Xp.Slate, outline = Xp.Edge2, error = Xp.Loss,
            surfaceContainer = Xp.Night, surfaceContainerHigh = Xp.Night2,
        ),
        typography = typo,
        content = content,
    )
}
