package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File

/**
 * One model handed to CuraEngine.
 *
 * The plate can hold several, and CuraEngine's CLI takes them as separate mesh groups
 * (a "--next" between them) rather than as one file. [file] is the object's staged mesh with its
 * plate placement already baked into its vertices, so the engine needs no per-object transform.
 *
 * The two Slic3r forks do not use this: their consoles accept a single file, so the whole plate
 * reaches them as one multi-object 3MF and their runners are unchanged.
 */
data class SliceModel(
    val file: File,
    /** What the engine labels the object with in the G-code. */
    val name: String,
    /** Support and blocker paint for this object, which becomes its modifier volumes. */
    val supportPaint: SupportPaintState = SupportPaintState(),
)
