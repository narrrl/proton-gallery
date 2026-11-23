package io.narl.protongallery

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The suite's corner radii, as in Proton Stream and the websites: 6 for a
 * badge or chip, 8 for a small control, 12 for a card, 16 for a sheet or a
 * floating panel, 24 for something that floats over a photo. Buttons and the
 * section switch are [Pill]s. Photo tiles stay square: the grid is one sheet of
 * photographs, not a stack of cards.
 */
internal val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

internal val Pill = RoundedCornerShape(percent = 50)
