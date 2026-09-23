package com.tomppi.enderslicer.model

/**
 * The slicing engine the user chose. Each engine has its own theme accent
 * (Cura blue, PrusaSlicer orange, OrcaSlicer teal), its own profile formats, its own G-code
 * dialect and its own engine binary. Profiles are NEVER combined: the user
 * picks one engine and the app behaves like that product.
 *
 * Settings are engine-scoped as well: [SlicerSettings], [PrusaSliceSettings] and
 * [OrcaSliceSettings] are separate models, each persisted under its own key, and a named user
 * preset belongs to exactly one engine.
 */
enum class SlicerEngine(val label: String) {
    CURA("Cura"),
    PRUSA("PrusaSlicer"),
    ORCA("OrcaSlicer"),
}
