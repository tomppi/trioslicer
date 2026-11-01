package com.tomppi.enderslicer.scanruler.engine

/** Where the embedded ScanRuler page is in its preparation. */
enum class ScanRulerPhase { IDLE, PREPARING, READY, FAILED }

/** One snapshot of the embedded engine, observed by the plate workspace. */
data class ScanRulerEmbeddedState(
    val phase: ScanRulerPhase = ScanRulerPhase.IDLE,
    val pageUrl: String? = null,
    val pagePort: Int = 0,
    val websocketPort: Int = 0,
    val paired: Boolean = false,
    val toolsReady: Boolean = false,
    val toolCount: Int = 0,
    val embedded: Boolean = false,
    val message: String? = null,
) {
    val ready: Boolean get() = phase == ScanRulerPhase.READY
}

/** A page command refused or failed. code is the page's own error code when it had one. */
class ScanRulerCommandException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The page's hello was unusable, or a link message broke the protocol. */
class ScanRulerLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
