package io.narl.protongallery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode { System, Light, Dark }

// Proton-style violet and neutral plum; gradients are reserved for the app mark.
private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4AFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE7FF), onPrimaryContainer = Color(0xFF382380),
    secondary = Color(0xFF5D536F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE7FF), onSecondaryContainer = Color(0xFF382380),
    tertiary = Color(0xFF74508B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0DFFC), onTertiaryContainer = Color(0xFF34203F),
    background = Color(0xFFFAF9FC), onBackground = Color(0xFF1B1340),
    surface = Color.White, onSurface = Color(0xFF1B1340),
    surfaceVariant = Color(0xFFF0EDF7), onSurfaceVariant = Color(0xFF615970),
    outline = Color(0xFF877F98), outlineVariant = Color(0xFFE5E0EE),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F5FC),
    surfaceContainer = Color(0xFFF1EEF8), surfaceContainerHigh = Color(0xFFEAE6F2),
    surfaceContainerHighest = Color(0xFFE3DEED), surfaceDim = Color(0xFFE3DEED), surfaceBright = Color.White,
    inverseSurface = Color(0xFF30283F), inverseOnSurface = Color(0xFFF6F1FF), inversePrimary = Color(0xFFBDA9FF),
    error = Color(0xFFB42339), onError = Color.White,
    errorContainer = Color(0xFFFDE9EE), onErrorContainer = Color(0xFF740F24),
    surfaceTint = Color(0xFF6D4AFF), scrim = Color.Black,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDA9FF), onPrimary = Color(0xFF28135F),
    primaryContainer = Color(0xFF372580), onPrimaryContainer = Color(0xFFEFE9FF),
    secondary = Color(0xFFBDB3D2), onSecondary = Color(0xFF302840),
    secondaryContainer = Color(0xFF372580), onSecondaryContainer = Color(0xFFEFE9FF),
    tertiary = Color(0xFFD6B4EB), onTertiary = Color(0xFF412450),
    tertiaryContainer = Color(0xFF5B3A6A), onTertiaryContainer = Color(0xFFF0DFFC),
    background = Color(0xFF171324), onBackground = Color(0xFFF4F0FF),
    surface = Color(0xFF1C182B), onSurface = Color(0xFFF4F0FF),
    surfaceVariant = Color(0xFF30283F), onSurfaceVariant = Color(0xFFBFB5D1),
    outline = Color(0xFF91849F), outlineVariant = Color(0xFF433850),
    surfaceContainerLowest = Color(0xFF120F1C), surfaceContainerLow = Color(0xFF211B31),
    surfaceContainer = Color(0xFF272036), surfaceContainerHigh = Color(0xFF30283F),
    surfaceContainerHighest = Color(0xFF392F49), surfaceDim = Color(0xFF171324), surfaceBright = Color(0xFF3C334B),
    inverseSurface = Color(0xFFEAE2F5), inverseOnSurface = Color(0xFF30283F), inversePrimary = Color(0xFF6D4AFF),
    error = Color(0xFFFFB2C0), onError = Color(0xFF650E24),
    errorContainer = Color(0xFF8C1931), onErrorContainer = Color(0xFFFFD9E1),
    surfaceTint = Color(0xFFBDA9FF), scrim = Color.Black,
)
private val GalleryType = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 21.sp, letterSpacing = (-0.3).sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
)

@Composable
fun GalleryTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = GalleryType, content = content)
}
