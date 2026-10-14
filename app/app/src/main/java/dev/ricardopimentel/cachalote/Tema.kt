package dev.ricardopimentel.cachalote

// GERADO por tools/design-to-compose.py a partir de design/sistema/tema.json.
// Não editar à mão: mexe no design e volta a correr o gerador.

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Sistema de design "Cachalote" — modo DARK.
val cachaloteColors = darkColorScheme(
    background = Color(0xFF0C141D),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    inverseOnSurface = Color(0xFF29313B),
    inversePrimary = Color(0xFF006B55),
    inverseSurface = Color(0xFFDBE3F0),
    onBackground = Color(0xFFDBE3F0),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    onPrimary = Color(0xFF00382B),
    onPrimaryContainer = Color(0xFF006952),
    onSecondary = Color(0xFF00363F),
    onSecondaryContainer = Color(0xFF003640),
    onSurface = Color(0xFFDBE3F0),
    onSurfaceVariant = Color(0xFFBACAC2),
    onTertiary = Color(0xFF003824),
    onTertiaryContainer = Color(0xFF006947),
    outline = Color(0xFF84948D),
    outlineVariant = Color(0xFF3B4A44),
    primary = Color(0xFFBEFFE7),
    primaryContainer = Color(0xFF38EFC2),
    secondary = Color(0xFF58D6F1),
    secondaryContainer = Color(0xFF00A7C0),
    surface = Color(0xFF0C141D),
    surfaceBright = Color(0xFF323A44),
    surfaceContainer = Color(0xFF182029),
    surfaceContainerHigh = Color(0xFF232B34),
    surfaceContainerHighest = Color(0xFF2D353F),
    surfaceContainerLow = Color(0xFF141C25),
    surfaceContainerLowest = Color(0xFF070F17),
    surfaceDim = Color(0xFF0C141D),
    surfaceTint = Color(0xFF15E0B4),
    surfaceVariant = Color(0xFF2D353F),
    tertiary = Color(0xFFC0FFDC),
    tertiaryContainer = Color(0xFF5FEDB0),
)

// Escala tipográfica do design. `mono` para as strings técnicas (ids, comandos, JSON).
val Mono = FontFamily.Monospace

val cachaloteTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight(600), lineHeight = 38.sp, letterSpacing = -0.02.em),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight(500), lineHeight = 32.sp, letterSpacing = -0.015.em),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight(500), lineHeight = 26.sp, letterSpacing = -0.01.em),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight(400), lineHeight = 24.sp, letterSpacing = 0.em),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight(400), lineHeight = 20.sp, letterSpacing = 0.01.em),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight(400), lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 18.sp, letterSpacing = -0.01.em),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight(500), lineHeight = 16.sp, letterSpacing = 0.em),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight(400), lineHeight = 14.sp, letterSpacing = 0.02.em),
)

// Escala de espaçamento do design (rem → dp, a 1rem = 16dp).
object Espaco {
    val xs = 4.dp
    val sm = 8.dp
    val gutter = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val margin = 16.dp
}

