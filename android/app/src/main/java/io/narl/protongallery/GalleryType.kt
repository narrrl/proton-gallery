package io.narl.protongallery

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Inter, as in Proton Stream, the desktop app and the suite's websites.
 *
 * A script Inter does not draw — CJK, Arabic, Devanagari — still renders: the
 * platform falls back glyph by glyph to the system font, so an album named in
 * any language reads as it did under the system family.
 */
internal val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_regular, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)

/** Inter's optical size for large text, for the sign-in headline. */
internal val InterDisplay = FontFamily(Font(R.font.inter_display_bold, FontWeight.Bold))

private fun style(size: Int, weight: FontWeight, line: Float = 1.35f, tracking: TextUnit = 0.sp, family: FontFamily = Inter) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = tracking)

/** Every Material style, so a stock component nobody restyled still lands on Inter. */
internal val GalleryType = Typography(
    displayLarge = style(40, FontWeight.Bold, 1.1f, (-1).sp, InterDisplay),
    displayMedium = style(34, FontWeight.Bold, 1.1f, (-0.8).sp, InterDisplay),
    displaySmall = style(30, FontWeight.Bold, 1.15f, (-0.6).sp, InterDisplay),
    headlineLarge = style(32, FontWeight.SemiBold, 1.2f, (-1).sp),
    headlineMedium = style(27, FontWeight.SemiBold, 1.2f, (-0.6).sp),
    headlineSmall = style(23, FontWeight.SemiBold, 1.25f, (-0.4).sp),
    titleLarge = style(21, FontWeight.SemiBold, 1.3f, (-0.3).sp),
    titleMedium = style(16, FontWeight.SemiBold),
    titleSmall = style(14, FontWeight.SemiBold),
    bodyLarge = style(16, FontWeight.Normal, 1.5f),
    bodyMedium = style(14, FontWeight.Normal, 1.45f),
    bodySmall = style(12, FontWeight.Normal),
    labelLarge = style(14, FontWeight.SemiBold),
    labelMedium = style(12, FontWeight.SemiBold),
    labelSmall = style(11, FontWeight.SemiBold),
)
