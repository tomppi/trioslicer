package com.tomppi.enderslicer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.model.SlicerEngine

/**
 * TrioSlicer palette ("engineering cockpit"), themed per slicing
 * engine: Cura (blue) and PrusaSlicer (orange) so the whole app visibly
 * changes identity with the engine choice. See
 * docs/ux-redesign/DESIGN_PROPOSAL.md.
 */

/** Cura blue accent. */
private val CuraDark = darkColorScheme(
    primary = Color(0xFF4CA2FF),
    onPrimary = Color(0xFF00274D),
    primaryContainer = Color(0xFF004B8F),
    onPrimaryContainer = Color(0xFFCFE7FF),
    inversePrimary = Color(0xFF0469C2),
    secondary = Color(0xFF99A6B3),
    onSecondary = Color(0xFF0E1116),
    secondaryContainer = Color(0xFF232C38),
    onSecondaryContainer = Color(0xFFD6DEE6),
    tertiary = Color(0xFF8FC4FF),
    onTertiary = Color(0xFF08131F),
    tertiaryContainer = Color(0xFF16344F),
    onTertiaryContainer = Color(0xFFCFE7FF),
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFF99A6B3),
    surfaceTint = Color(0xFF4CA2FF),
    inverseSurface = Color(0xFFE8EEF4),
    inverseOnSurface = Color(0xFF1E2733),
    outline = Color(0xFF2E3947),
    outlineVariant = Color(0xFF232C38),
    error = Color(0xFFF0655D),
    onError = Color(0xFF2A0705),
    errorContainer = Color(0xFF4A1412),
    onErrorContainer = Color(0xFFFFB4AC),
    scrim = Color(0xFF000000),
)

private val CuraLight = lightColorScheme(
    primary = Color(0xFF0668C5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E7FF),
    onPrimaryContainer = Color(0xFF002F5F),
    inversePrimary = Color(0xFF4CA2FF),
    secondary = Color(0xFF4D5A66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E5EF),
    onSecondaryContainer = Color(0xFF131A20),
    tertiary = Color(0xFF1C5B93),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDE6FF),
    onTertiaryContainer = Color(0xFF04192E),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A212A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A212A),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF4D5A66),
    surfaceTint = Color(0xFF0668C5),
    inverseSurface = Color(0xFF2E3947),
    inverseOnSurface = Color(0xFFF7F9FC),
    outline = Color(0xFF77828D),
    outlineVariant = Color(0xFFD9E0E7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

/** PrusaSlicer orange accent. */
private val PrusaDark = darkColorScheme(
    primary = Color(0xFFFF9E45),
    onPrimary = Color(0xFF261200),
    primaryContainer = Color(0xFF5A2E00),
    onPrimaryContainer = Color(0xFFFFD7A8),
    inversePrimary = Color(0xFFA84F00),
    secondary = Color(0xFF99A6B3),
    onSecondary = Color(0xFF0E1116),
    secondaryContainer = Color(0xFF232C38),
    onSecondaryContainer = Color(0xFFD6DEE6),
    tertiary = Color(0xFFFFC27F),
    onTertiary = Color(0xFF261200),
    tertiaryContainer = Color(0xFF4A2F14),
    onTertiaryContainer = Color(0xFFFFE2C2),
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFF99A6B3),
    surfaceTint = Color(0xFFFF9E45),
    inverseSurface = Color(0xFFE8EEF4),
    inverseOnSurface = Color(0xFF1E2733),
    outline = Color(0xFF2E3947),
    outlineVariant = Color(0xFF232C38),
    error = Color(0xFFF0655D),
    onError = Color(0xFF2A0705),
    errorContainer = Color(0xFF4A1412),
    onErrorContainer = Color(0xFFFFB4AC),
    scrim = Color(0xFF000000),
)

private val PrusaLight = lightColorScheme(
    primary = Color(0xFFD95E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC5),
    onPrimaryContainer = Color(0xFF3D1A00),
    inversePrimary = Color(0xFFFF9E45),
    secondary = Color(0xFF4D5A66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E5EF),
    onSecondaryContainer = Color(0xFF131A20),
    tertiary = Color(0xFF8A4500),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC5),
    onTertiaryContainer = Color(0xFF2C1100),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A212A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A212A),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF4D5A66),
    surfaceTint = Color(0xFFD95E00),
    inverseSurface = Color(0xFF2E3947),
    inverseOnSurface = Color(0xFFF7F9FC),
    outline = Color(0xFF77828D),
    outlineVariant = Color(0xFFD9E0E7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

/**
 * OrcaSlicer's teal accent over the PrusaSlicer palette.
 *
 * Only the accent differs: the rest of the scheme is shared so the two PrusaSlicer-derived
 * engines look consistent with each other and with Cura, and nothing has to restate a palette.
 */
private val OrcaDark = PrusaDark.copy(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFB2DFDB),
)

private val OrcaLight = PrusaLight.copy(
    primary = Color(0xFF00796B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
)

@Composable
fun EnderSlicerTheme(
    engine: SlicerEngine = SlicerEngine.CURA,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        dark && engine == SlicerEngine.PRUSA -> PrusaDark
        dark && engine == SlicerEngine.ORCA -> OrcaDark
        dark -> CuraDark
        !dark && engine == SlicerEngine.PRUSA -> PrusaLight
        !dark && engine == SlicerEngine.ORCA -> OrcaLight
        else -> CuraLight
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

/**
 * Shared layout tokens for the app UI (4 px grid). New screens should use
 * these instead of ad-hoc dp values; see docs/ui-style-guide.md.
 */
object EnderSlicerDimens {
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space6 = 6.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val TouchTarget = 48.dp
    val CardPadding = 12.dp
    val SheetPadding = 12.dp
}
