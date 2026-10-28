package com.tomppi.enderslicer.ui

import java.util.Locale

/** The extensions SolidCraft's Open route accepts, lower-case for a canonical comparison. */
internal val CAD_DOCUMENT_EXTENSIONS =
    setOf("step", "stp", "iges", "igs", "3mf", "obj", "glb", "gltf", "solidcraft")

/**
 * The name a CAD file should be opened under, or null when it is not a CAD document.
 *
 * Android's document providers are free to make a displayed name unique by appending " (1)",
 * and they do it on either side of the extension: "part.step (1)" (the provider's usual
 * choice for a duplicate save) and "part (1).step" both occur. The extension gate must look at
 * the real extension, not at the provider's suffix - otherwise opening the STEP the app itself
 * just saved fails on the very name the provider gave it. The returned name has the suffix
 * stripped so the workspace and design names stay readable; the caller copies the bytes to
 * that canonical name rather than trusting the destination name.
 */
internal fun cadDocumentOpenName(displayName: String): String? {
    var name = displayName.trim()
    if (name.isEmpty()) return null
    // Strip a trailing or pre-extension duplicate counter, repeatedly, so a doubly suffixed
    // name ("part (1).step (2)") still canonicalises to "part.step".
    var changed = true
    while (changed) {
        changed = false
        val beforeExtension = name.replace(Regex("\\s*\\(\\d+\\)(?=\\.[A-Za-z0-9]+$)"), "")
        if (beforeExtension != name) {
            name = beforeExtension.trim()
            changed = true
        }
        val afterExtension = name.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
        if (afterExtension != name) {
            name = afterExtension.trim()
            changed = true
        }
    }
    val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
    if (extension !in CAD_DOCUMENT_EXTENSIONS) return null
    return name
}
