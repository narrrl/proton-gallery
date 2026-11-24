package io.narl.protongallery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { System, Light, Dark }

/*
 * The suite's colours (docs/ANDROID.md, "Visual direction"): the same violet,
 * plum neutrals and copper as Proton Stream, Proton Drive for Linux and the
 * websites. Light is the suite's pale lavender page; dark is its plum-black.
 */
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
    inverseSurface = Color(0xFF2E283D), inverseOnSurface = Color(0xFFF4F2FA), inversePrimary = Color(0xFFA48BFF),
    error = Color(0xFFB42339), onError = Color.White,
    errorContainer = Color(0xFFFDE9EE), onErrorContainer = Color(0xFF740F24),
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    // Violet 400 rather than 500: Material also writes text and icons in
    // `primary`, and 500 on the plum-black page is under 4:1. White stays
    // above 3.5:1 on it as a fill.
    primary = Color(0xFF8A6DFF), onPrimary = Color.White,
    primaryContainer = Color(0xFF372580), onPrimaryContainer = Color(0xFFEFE9FF),
    secondary = Color(0xFFBDB3D2), onSecondary = Color(0xFF2E283D),
    secondaryContainer = Color(0xFF372580), onSecondaryContainer = Color(0xFFEFE9FF),
    tertiary = Color(0xFFD6B4EB), onTertiary = Color(0xFF412450),
    tertiaryContainer = Color(0xFF5B3A6A), onTertiaryContainer = Color(0xFFF0DFFC),
    background = Color(0xFF100E17), onBackground = Color(0xFFF4F2FA),
    surface = Color(0xFF17141F), onSurface = Color(0xFFF4F2FA),
    surfaceVariant = Color(0xFF1E1A29), onSurfaceVariant = Color(0xFFAAA4BD),
    outline = Color(0xFF7D7791), outlineVariant = Color(0xFF2E283D),
    surfaceContainerLowest = Color(0xFF0B0910), surfaceContainerLow = Color(0xFF17141F),
    surfaceContainer = Color(0xFF1E1A29), surfaceContainerHigh = Color(0xFF262133),
    surfaceContainerHighest = Color(0xFF2E283D), surfaceDim = Color(0xFF100E17), surfaceBright = Color(0xFF3A324B),
    inverseSurface = Color(0xFFF4F2FA), inverseOnSurface = Color(0xFF1E1A29), inversePrimary = Color(0xFF6D4AFF),
    error = Color(0xFFFFB2C0), onError = Color(0xFF650E24),
    errorContainer = Color(0xFF8C1931), onErrorContainer = Color(0xFFFFD9E1),
    surfaceTint = Color.Transparent, scrim = Color.Black,
)

/**
 * The suite's colours that Material has no role for.
 *
 * [accent] is violet 500, flat: the selected section, the sign-in button and
 * the activity bar wear it. It is 500 in both modes, where Material's dark
 * `primary` is the lighter 400, because white text sits on it. The
 * violet-to-orchid ramp belongs to the icons' faces and nowhere else: across
 * buttons and bars it read as decoration rather than a decision. [copper] is
 * the metal behind the icons, for small things only — a badge, the sun on the
 * map — and [Copper] is its scale, so a tint or a border takes a step of it
 * rather than a mix of its own.
 */
@Immutable
data class Brand(val accent: Color, val copper: Color, val onCopper: Color)

/** The suite's copper scale, the same steps as Proton Stream's `appearance::copper`. */
object Copper {
    val C100 = Color(0xFFFBEBDD)
    val C200 = Color(0xFFF2C9AA)
    val C300 = Color(0xFFE5B08D)
    val C400 = Color(0xFFD69B77)
    val C500 = Color(0xFFB47456)
    val C600 = Color(0xFF8F5940)
    val C700 = Color(0xFF703E30)
}

private val LightBrand = Brand(
    accent = Color(0xFF6D4AFF),
    copper = Copper.C500,
    onCopper = Color.White,
)
private val DarkBrand = Brand(
    accent = Color(0xFF6D4AFF),
    copper = Copper.C400,
    onCopper = Color(0xFF100E17),
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }

@Composable
fun GalleryTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = GalleryType,
            shapes = GalleryShapes,
            content = content,
        )
    }
}
