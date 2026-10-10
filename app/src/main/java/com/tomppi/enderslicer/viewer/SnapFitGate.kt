package com.tomppi.enderslicer.viewer

/**
 * The watertight gate every half goes through before a boolean touches it.
 *
 * Manifold refuses a mesh with a hole in it, and that refusal arrives as a bare
 * "NotManifold" from deep inside a boolean. So the question is asked first and
 * on its own: is this half closed, and if not, can [MeshRepair] close it? A half
 * that is still open afterwards is refused with the repair's own report - what
 * could not be closed, and why - and nothing is booleaned onto it. The agreed
 * default is to refuse the snap politely rather than mangle the model.
 *
 * The engine is behind [Engine] so the decision can be tested without the
 * native library, which is only packaged for arm64-v8a.
 */
object SnapFitGate {
    /**
     * The engine's own success word, as [MeshBoolean] reports it. Duplicated
     * rather than shared because a status is a string the native side owns;
     * comparing it here is the same test [MeshBoolean.isManifold] makes.
     */
    const val NO_ERROR = "NoError"

    /** The native calls this gate makes: a manifold verdict, the repair, and the two booleans. */
    interface Engine {
        fun manifoldStatus(mesh: StlMesh): String?
        fun repair(mesh: StlMesh): MeshRepair.Result
        fun union(first: StlMesh, second: StlMesh): MeshBoolean.Result
        fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result
    }

    /** What [MeshBoolean] and [MeshRepair] answer with on a device. */
    object NativeEngine : Engine {
        override fun manifoldStatus(mesh: StlMesh): String? = MeshBoolean.manifoldStatus(mesh)

        override fun repair(mesh: StlMesh): MeshRepair.Result = MeshRepair.repair(mesh)

        override fun union(first: StlMesh, second: StlMesh): MeshBoolean.Result =
            MeshBoolean.union(first, second)

        override fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result =
            MeshBoolean.subtract(first, second)
    }

    /** One half, ready for a boolean, and how it got that way. */
    data class Ready(
        val mesh: StlMesh,
        /** The repair's own line, or null when the half was closed to begin with. */
        val note: String?,
    )

    sealed interface Result {
        data class Prepared(val ready: Ready) : Result

        /** Nothing may be booleaned onto this half; [reason] names what is still open. */
        data class Refused(val reason: String) : Result
    }

    /**
     * [mesh], if the engine will take it: already closed, or repaired into a
     * closed mesh, or refused with the reason the repair could not close it.
     */
    fun prepare(mesh: StlMesh, engine: Engine, name: String): Result {
        if (engine.manifoldStatus(mesh) == NO_ERROR) return Result.Prepared(Ready(mesh, null))
        val repaired = engine.repair(mesh)
        if (engine.manifoldStatus(repaired.mesh) == NO_ERROR) {
            // The report's own line already says what was closed and why anything
            // was not: "closed 1 hole (4 edges)".
            return Result.Prepared(Ready(repaired.mesh, repaired.report.summary))
        }
        return Result.Refused(
            name + " is not watertight and could not be closed: " + repaired.report.summary,
        )
    }
}
