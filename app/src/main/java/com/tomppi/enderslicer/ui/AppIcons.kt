package com.tomppi.enderslicer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Brand icon set for the redesigned navigation and hub screens.
 * 24x24 filled glyphs; SVG path data kept here so the app has no
 * material-icons-extended dependency. See docs/ux-redesign/mockups.
 */
object AppIcons {

    private fun glyph(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()

    /** Bottom navigation: build plate. */
    val Plate: ImageVector by lazy {
        glyph(
            "Plate",
            "M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z",
        )
    }

    /** Bottom navigation: print settings (tune). */
    val Settings: ImageVector by lazy {
        glyph(
            "Settings",
            "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
        )
    }

    /** Bottom navigation: print session (power). */
    val Print: ImageVector by lazy {
        glyph(
            "Print",
            "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z",
        )
    }

    /** Bottom navigation: more. */
    val More: ImageVector by lazy {
        glyph(
            "More",
            "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        )
    }

    /** Model / plate chip. */
    val Cube: ImageVector by lazy {
        glyph(
            "Cube",
            "M12.2 2.6l7 4.1a1 1 0 0 1 .5.9v8.8a1 1 0 0 1-.5.9l-7 4.1a1 1 0 0 1-1 0l-7-4.1a1 1 0 0 1-.5-.9V7.6a1 1 0 0 1 .5-.9l7-4.1a1 1 0 0 1 1 0zM12 4.3L6.6 7.2 12 10.1l5.4-2.9L12 4.3zM6.2 9v5.8l5.3 3.1v-5.9L6.2 9zm11.6 0l-5.3 3v5.9l5.3-3.1V9z",
        )
    }

    /** Viewer mode: layers. */
    val Layers: ImageVector by lazy {
        glyph(
            "Layers",
            "M12 2.8l9 5-9 5-9-5 9-5zM3.3 13.2L12 18l8.7-4.8.8 1.7-9.5 5.3-9.5-5.3.8-1.7zM3.3 17l9.5 5.2 9.5-5.2.8 1.7L12 22.8 2.5 18.7l.8-1.7z",
        )
    }

    /** Viewer mode: nozzle path. */
    val Wave: ImageVector by lazy {
        glyph(
            "Wave",
            "M2.6 12.1c2.3-4.9 4.8-4.9 7.2 0 2.4 4.9 4.9 4.9 7.2 0 1.1-2.3 2.2-3.2 4.4-2.2l-.9 1.8c-1.4-.6-2.1-.1-2.9 1.3-2.5 5.2-6.7 5.2-9.4 0-2.2-4.5-3.4-4.5-5.6 0l-1-1z",
        )
    }

    /** More hub: BumpMesh camera. */
    val Camera: ImageVector by lazy {
        glyph(
            "Camera",
            "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z",
        )
    }

    /** More hub: experimental bolt / fast action. */
    val Bolt: ImageVector by lazy {
        glyph(
            "Bolt",
            "M13 2.5L4.5 14H10l-1 7.5L17.5 10H12l1-7.5z",
        )
    }

    /** More hub: mesh triangle limit. */
    val Filter: ImageVector by lazy {
        glyph(
            "Filter",
            "M4.25 5.61C6.27 8.2 10 13 10 13v6c0 .55.45 1 1 1h2c.55 0 1-.45 1-1v-6s3.72-4.8 5.74-7.39C20.25 4.95 19.78 4 18.95 4H5.04c-.83 0-1.3.95-.79 1.61z",
        )
    }

    /** More hub: profiles. */
    val Star: ImageVector by lazy {
        glyph(
            "Star",
            "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
        )
    }

    /** More hub: printer / machine. */
    val Machine: ImageVector by lazy {
        glyph(
            "Machine",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
        )
    }

    /** More hub: configuration snapshot swap. */
    val Swap: ImageVector by lazy {
        glyph(
            "Swap",
            "M6.99 11L3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z",
        )
    }

    /** More hub: about info. */
    val Info: ImageVector by lazy {
        glyph(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
        )
    }

    /** More hub: safety shield. */
    val Shield: ImageVector by lazy {
        glyph(
            "Shield",
            "M12 2l8 3.1V11c0 5.1-3.42 9.9-8 11-4.58-1.1-8-5.9-8-11V5.1L12 2zm-1.2 14.4l5.0-5.2-1.4-1.5-3.6 3.7-1.7-1.7-1.4 1.5 3.1 3.2z",
        )
    }

    /** Blender engine: AI assistant. Two sparkles, main and satellite. */
    val Sparkle: ImageVector by lazy {
        glyph(
            "Sparkle",
            "M11 3l1.7 4.6L17.3 9l-4.6 1.7L11 15.3 9.3 10.7 4.7 9l4.6-1.4L11 3z" +
                "M18 13.5l.9 2.4 2.4.9-2.4.9-.9 2.4-.9-2.4-2.4-.9 2.4-.9.9-2.4z",
        )
    }

    /** Blender engine: upload an image. Arrow into a tray. */
    val ImageUpload: ImageVector by lazy {
        glyph(
            "ImageUpload",
            "M9 16h6v-6h4l-7-7-7 7h4v6zM5 18h14v2H5v-2z",
        )
    }

    /** Blender engine: send a chat message. */
    val Send: ImageVector by lazy {
        glyph(
            "Send",
            "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z",
        )
    }

    /** Blender engine: collapse the floating chat to its bubble. */
    val Minimise: ImageVector by lazy {
        glyph(
            "Minimise",
            "M19 13H5v-2h14v2z",
        )
    }
}
