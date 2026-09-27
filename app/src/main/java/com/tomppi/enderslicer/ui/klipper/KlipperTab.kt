package com.tomppi.enderslicer.ui.klipper

import androidx.compose.ui.graphics.vector.ImageVector
import com.tomppi.enderslicer.ui.AppIcons

/**
 * The screens the printer destination is divided into.
 *
 * The order is the order a print happens in: what the machine is doing, then what it is
 * set to, then moving it, then feeding it, then the things that are run on it, the files
 * it prints, what it says, the bed it measures, what it has done, and finally the
 * machine and the host running it.
 *
 * Every one of them is always there, whether or not the printer has the section it
 * describes. A tab that appears and disappears is a tab nobody learns where to find.
 */
internal enum class KlipperTab(val label: String) {
    DASHBOARD("Dashboard"),
    TEMPERATURES("Temperatures"),
    MOVE("Move"),
    EXTRUDE("Extrude"),
    MACROS("Macros"),
    FILES("Files"),
    CONSOLE("Console"),
    ZPROBE("Z probe"),
    MESH("Mesh"),
    HISTORY("History"),
    MACHINE("Machine"),
    ;

    companion object {
        /** The screen a saved name refers to, or the dashboard when it means nothing. */
        fun named(name: String?): KlipperTab =
            entries.firstOrNull { it.name == name } ?: DASHBOARD
    }
}

/** The glyph a screen is drawn with. */
internal val KlipperTab.icon: ImageVector
    get() = when (this) {
        KlipperTab.DASHBOARD -> AppIcons.Dashboard
        KlipperTab.TEMPERATURES -> AppIcons.Thermometer
        KlipperTab.MOVE -> AppIcons.Move
        KlipperTab.EXTRUDE -> AppIcons.Extrude
        KlipperTab.MACROS -> AppIcons.Macro
        KlipperTab.FILES -> AppIcons.Folder
        KlipperTab.CONSOLE -> AppIcons.Console
        KlipperTab.ZPROBE -> AppIcons.Probe
        KlipperTab.MESH -> AppIcons.Mesh
        KlipperTab.HISTORY -> AppIcons.History
        KlipperTab.MACHINE -> AppIcons.Wrench
    }
