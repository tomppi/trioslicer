package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import com.tomppi.enderslicer.viewer.unionOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One engine body and the plate object that draws it.
 *
 * A document can hold several parts - a case and its lid - and the plate draws each as its own
 * object, at the document's own coordinates and under the engine's own body name. [objectId]
 * is the plate object's identity: it survives a repaint so the camera does not reset and an
 * edit patches exactly the part it named.
 */
data class CadPart(
    val body: String,
    val objectId: String,
)

/**
 * What the plate is showing when the model is a CAD document rather than a mesh.
 *
 * The document's source of truth is [designPath] - a .solidcraft file the engine writes and
 * owns. The triangles on screen are a tessellation of that document, derived state with its
 * own cache file: refreshing them is a view update, never a new model, and replacing them must
 * not touch the camera, the placement or the plate's selection.
 *
 * The plate holds **one object per part**: [parts] names the body each plate object draws, and
 * every plate function - move, rotate, scale, split, snap fit, paint, arrange - acts on that
 * part alone. The document stays one document (one engine session, one design file, one
 * identity for the agent); it is the drawing of it that is per part. A single-body design looks
 * exactly as it always did, because one body is one object.
 *
 * [converted] says the plate now holds the STLs a Slice takes, exported by the human's own
 * Convert to STL press. The document stays open in the engine and the agent can keep editing
 * it; the plate's converted objects are not live geometry, so an edit after conversion marks
 * the plate stale ([derivedStale]) instead of painting under the human.
 *
 * [displayRevision] moves once per applied edit.
 */
data class CadDocument(
    /** The design's name, shown on the plate and used for its files. */
    val name: String,
    /** The .solidcraft document the engine owns; the model's source of truth. */
    val designPath: String,
    /** The STEP/IGES/3MF/OBJ/glTF file it was opened from, when it was opened from one. */
    val sourcePath: String? = null,
    /**
     * The 1.9.6 single plate object that drew the whole tessellation.
     *
     * Legacy: a descriptor written before per-part objects restored a plate with one object for
     * the entire document. The first republish replaces it with [parts]; nothing new writes it.
     */
    val displayObjectId: String? = null,
    /** Legacy: the 1.9.6 display cache file; per-part objects carry their own source paths. */
    val displayMeshPath: String? = null,
    /** One plate object per body of the live document, in the engine's body order. */
    val parts: List<CadPart> = emptyList(),
    /** True when the plate holds STLs made from this document by the human's Convert press. */
    val converted: Boolean = false,
    /** The display revision those STLs were exported from. */
    val convertedRevision: Long = -1L,
    /** One plate object per part of the converted plate, so Convert again replaces in place. */
    val convertedParts: List<CadPart> = emptyList(),
    val displayRevision: Long = 0L,
    val triangleCount: Int = 0,
    val bodyCount: Int = 0,
    /** True while an edit's tessellation is being applied; the last one stays on screen. */
    val updating: Boolean = false,
    /**
     * True when the plate holds a split/snap layout made from the document's previous shape,
     * so the plate no longer draws the current document.
     *
     * The app does not paste a new shape over a human's split: it says what happened and the
     * agent sheet's Rebuild plate is how the new shape is taken.
     */
    val derivedStale: Boolean = false,
    val staleReason: String? = null,
    /** End-to-end milliseconds of the last applied edit: call answer to new mesh in state. */
    val lastEditMillis: Long? = null,
    /** How many edits this session has applied. */
    val editCount: Int = 0,
    /** The print STL the last Slice wrote, and the display revision it was written from. */
    val sliceStlPath: String? = null,
    val sliceExportRevision: Long = -1L,
    val sliceExportAtMillis: Long = 0L,
    val sliceTriangles: Int = 0,
) {
    /** Every plate object id this document owns, live parts and converted parts alike. */
    fun objectIds(): Set<String> = parts.mapTo(LinkedHashSet()) { it.objectId }

    /** True when [objectId] is one of the document's own plate objects. */
    fun isCadObject(objectId: String): Boolean = parts.any { it.objectId == objectId }

    /** The body a plate object draws, or null when the object is not this document's. */
    fun bodyOf(objectId: String): String? = parts.firstOrNull { it.objectId == objectId }?.body

    /** The converted object that draws [body], or null when it is not converted. */
    fun convertedObjectOf(body: String): String? =
        convertedParts.firstOrNull { it.body == body }?.objectId

    /**
     * Legacy: true when the 1.9.6 slice handoff would export the document again.
     *
     * Kept only so the file compiles while the view model still names it; the slice handoff is
     * gone in this release and nothing may convert or export the document to slice it.
     */
    @Deprecated("Slice converts nothing; Convert to STL is the human's own press")
    val needsSliceExport: Boolean
        get() {
            val path = sliceStlPath ?: return true
            return sliceExportRevision != displayRevision ||
                !File(path).isFile ||
                File(path).length() == 0L
        }
}

/**
 * What one agent call changed, as the engine reported it.
 *
 * Every feature command answers with created/changed/removed body names. That is what makes the
 * live view incremental: only those bodies are exported and re-tessellated, the rest of the
 * plate's geometry keeps the objects and buffers it already has. [fullRefresh] is the honest
 * fallback for the calls that do not name bodies (undo, redo, a parameter edit that rebuilds
 * everything, a newly opened file): re-read the document body by body.
 */
data class DocumentDelta(
    /** The MCP tool the agent called, e.g. execute or batch. */
    val tool: String,
    /** The engine command, for a single execute: solid.box, solid.fillet... */
    val command: String? = null,
    val createdBodies: List<String> = emptyList(),
    val changedBodies: List<String> = emptyList(),
    val removedBodies: List<String> = emptyList(),
    val fullRefresh: Boolean = false,
    /** The file an agent's open call brought in, when the call was one: it names the document. */
    val openedPath: String? = null,
) {
    /** The bodies whose triangles may have moved. */
    val repaintBodies: List<String>
        get() = (createdBodies + changedBodies).distinct()

    /** Nothing to redraw: the call changed something the geometry did not depend on. */
    val isEmpty: Boolean
        get() = !fullRefresh && repaintBodies.isEmpty() && removedBodies.isEmpty()

    /**
     * Two edits that arrived while one repaint was in flight, as one repaint.
     *
     * This is backlog coalescing, not a debounce: the first edit is always repainted as soon as
     * it is reported, and an edit is only merged when the previous one is still being applied.
     * A removal always wins over a change of the same body, because a removed body cannot be
     * exported to repaint.
     */
    fun mergedWith(next: DocumentDelta): DocumentDelta {
        val removed = (removedBodies + next.removedBodies).distinct()
        val created = (createdBodies + next.createdBodies).distinct().filterNot { it in removed }
        val changed = (changedBodies + next.changedBodies).distinct().filterNot { it in removed }
        return DocumentDelta(
            tool = next.tool,
            command = next.command ?: command,
            createdBodies = created,
            changedBodies = changed,
            removedBodies = removed,
            fullRefresh = fullRefresh || next.fullRefresh,
            openedPath = next.openedPath ?: openedPath,
        )
    }
}

/**
 * The plate's live tessellation, one mesh per body of the engine's design.
 *
 * The engine exports a body at a time (file.export with a bodies list), so an edit re-exports
 * only the bodies it touched. This keeps the per-body meshes and composes the one [StlMesh] the
 * plate's view draws. Composition is a concatenation of already-tessellated triangles - local
 * memory work, no engine call - and a plate of one body borrows that body's mesh instead of
 * copying it.
 *
 * Not thread-safe; the view model owns one and touches it from one coroutine at a time.
 */
/** One body of the document, and which of its own triangles a scene triangle is. */
data class BodyTriangle(val body: String, val mesh: StlMesh, val triangleIndex: Int)

class CadBodyMeshes {

    private val bodies = LinkedHashMap<String, StlMesh>()

    val count: Int get() = bodies.size

    fun names(): List<String> = bodies.keys.toList()

    fun mesh(name: String): StlMesh? = bodies[name]

    /**
     * Which body a triangle of [compose]'s concatenation belongs to, and its index there.
     *
     * [compose] walks the bodies in insertion order, so a scene triangle is its body's own index
     * plus that body's place in the run - which is what turns a tap's triangle into "the face of
     * Body2" instead of a number nothing can name.
     */
    fun locate(sceneTriangleIndex: Int): BodyTriangle? {
        if (sceneTriangleIndex < 0) return null
        var first = 0
        for ((name, mesh) in bodies) {
            val next = first + mesh.triangleCount
            if (sceneTriangleIndex < next) return BodyTriangle(name, mesh, sceneTriangleIndex - first)
            first = next
        }
        return null
    }

    /** Where [name]'s triangles start in [compose]'s concatenation, or null if it is not there. */
    fun firstTriangleOf(name: String): Int? {
        var first = 0
        for ((body, mesh) in bodies) {
            if (body == name) return first
            first += mesh.triangleCount
        }
        return null
    }

    fun put(name: String, mesh: StlMesh) {
        bodies[name] = mesh
    }

    fun remove(names: Collection<String>) {
        for (name in names) bodies.remove(name)
    }

    fun clear() = bodies.clear()

    /**
     * The whole design as the one mesh the plate draws, or null while it has no bodies.
     *
     * [displayName] is the document's name and must stay the same across edits: the model view
     * resets its camera when a mesh arrives under a different name, and the camera belongs to
     * the user.
     */
    fun compose(displayName: String): StlMesh? {
        if (bodies.isEmpty()) return null
        if (bodies.size == 1) {
            // One body is the document: share its vertex data rather than copying it.
            return bodies.values.first().copy(displayName = displayName)
        }
        val totalTriangles = bodies.values.sumOf { it.triangleCount }
        if (totalTriangles <= 0) return null
        val totalFloats = Math.multiplyExact(totalTriangles, FLOATS_PER_TRIANGLE)
        val heap = totalTriangles < OFF_HEAP_MIN_TRIANGLES
        val array = if (heap) FloatArray(totalFloats) else null
        val direct = if (heap) {
            null
        } else {
            ByteBuffer.allocateDirect(totalFloats * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        var written = 0
        for (mesh in bodies.values) {
            val floats = mesh.triangleCount * FLOATS_PER_TRIANGLE
            val source = mesh.interleavedVertices
            val sourceArray = source.arrayOrNull()
            if (array != null) {
                if (sourceArray != null) {
                    System.arraycopy(sourceArray, 0, array, written, floats)
                } else {
                    source.directOrNull()!!.duplicate().apply {
                        position(0)
                        limit(floats)
                    }.get(array, written, floats)
                }
            } else {
                val target = direct!!
                target.position(written)
                if (sourceArray != null) {
                    target.put(sourceArray, 0, floats)
                } else {
                    target.put(
                        source.directOrNull()!!.duplicate().apply {
                            position(0)
                            limit(floats)
                        },
                    )
                }
            }
            written += floats
        }
        return StlMesh(
            displayName = displayName,
            interleavedVertices = if (array != null) VertexData.fromArray(array) else VertexData.fromDirect(direct!!),
            triangleCount = totalTriangles,
            bounds = bodies.values.map { it.bounds }.unionOrNull() ?: return null,
        )
    }

    private companion object {
        /** Position + normal per vertex, three vertices: the layout StlParser writes. */
        const val FLOATS_PER_TRIANGLE = 18

        /** The same off-heap threshold StlParser applies to a single mesh. */
        const val OFF_HEAP_MIN_TRIANGLES = 200_000
    }
}

/**
 * Decides what a repaint does with one [DocumentDelta].
 *
 * Separated from the view model so the incremental handoff - the part that must not re-read the
 * whole document - is testable on the JVM: it says which bodies to export and applies their
 * parsed meshes to the composer, or reports that the delta cannot be applied incrementally and
 * the document must be re-read.
 */
object CadLiveUpdate {

    /** Mounts a parsed body mesh reported by a delta; false when the engine named none of them. */
    fun applyIncremental(
        composer: CadBodyMeshes,
        delta: DocumentDelta,
        meshOf: (String) -> StlMesh?,
    ): Boolean {
        composer.remove(delta.removedBodies)
        for (name in delta.repaintBodies) {
            val mesh = meshOf(name) ?: return false
            composer.put(name, mesh)
        }
        return true
    }
}

/** The engine's and the app's files for the CAD document, all inside the one --allow-path. */
class CadWorkspace(root: File) {

    val root: File = root

    val designs = File(root, "designs")
    val imports = File(root, "imports")
    val display = File(root, "display")
    val exports = File(root, "exports")

    /**
     * The STLs a Convert to STL press wrote.
     *
     * Converted parts are the plate's own models afterwards, so they live here rather than in
     * [display]: deleting a design must not take them with it, and a Slice reads them like any
     * imported mesh.
     */
    val converted = File(root, "converted")

    fun ensureDirectories(): CadWorkspace {
        for (directory in listOf(root, designs, imports, display, exports, converted)) {
            check(directory.isDirectory || directory.mkdirs()) {
                "could not create " + directory.absolutePath
            }
        }
        return this
    }

    fun designFile(name: String): File = File(designs, slug(name) + DESIGN_EXTENSION)

    /** Every design the workspace holds, newest first. */
    fun designFiles(): List<File> = designs.listFiles { file ->
        file.isFile && file.name.endsWith(DESIGN_EXTENSION)
    }.orEmpty().sortedByDescending { it.lastModified() }

    /**
     * The engine's display cache files for a design: its per-body tessellations and the old
     * single-file cache. Derived from the design, so they go when it does.
     */
    fun designCacheFiles(name: String): List<File> {
        val stem = slug(name)
        return display.listFiles().orEmpty().filter { file ->
            file.name == stem + "-display.stl" || file.name.startsWith(stem + "-body-")
        }
    }

    /** One part's converted STL, named after the design and the body. */
    fun convertedFile(document: String, body: String): File =
        File(converted, slug(document) + "-" + slug(body) + ".stl")

    /** A staged copy of a file being opened; the engine can only read inside the workspace. */
    fun importFile(sourceName: String): File {
        val safe = safeName(sourceName)
        return File(imports, System.nanoTime().toString() + "-" + safe)
    }

    /** The display tessellation of the whole document; a view cache, never the model. */
    fun displayFile(name: String): File = File(display, slug(name) + "-display.stl")

    /** One body's tessellation, exported on its own so an edit re-tessellates only it. */
    fun bodyFile(document: String, body: String): File =
        File(display, slug(document) + "-body-" + slug(body) + ".stl")

    /** The deliberate print export a Slice consumes. */
    fun printFile(name: String, stampMillis: Long): File =
        File(exports, slug(name) + "-print-" + stampMillis + ".stl")

    /** An artifact the user asked for by hand: STEP, STL, 3MF... */
    fun exportFile(name: String, extension: String): File =
        File(exports, slug(name) + "." + extension.lowercase().filter(Char::isLetterOrDigit))

    companion object {
        /** The engine's design container: the source of truth for a document. */
        const val DESIGN_EXTENSION = ".solidcraft"

        private const val MAX_SLUG = 48

        /** A file-safe, path-safe stem. Nothing here may contain a separator or climb a tree. */
        fun slug(text: String): String {
            val cleaned = text.trim().lowercase()
                .map { character ->
                    if (character.isLetterOrDigit() || character == '-' || character == '_') character else '-'
                }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(MAX_SLUG)
            return cleaned.ifBlank { "design" }
        }

        /** The source file's own name, reduced to one path component. */
        fun safeName(text: String): String =
            text.substringAfterLast('/').substringAfterLast('\\')
                .map { character ->
                    if (character.isLetterOrDigit() || character == '.' || character == '-' || character == '_') {
                        character
                    } else {
                        '_'
                    }
                }
                .joinToString("")
                .trim('.', '_', '-')
                .take(96)
                .ifBlank { "design" }
    }
}

/**
 * What a Slice does with the CAD document: export it once, then reuse that artifact while the
 * document has not moved on.
 *
 * The first slice after opening always exports: the workspace has no print STL for this
 * document yet. A later slice reuses the file only when neither the document nor the file
 * changed, saying which file it reused - the status line names what was exported and when.
 */
object CadSliceHandoff {

    sealed interface Decision {
        /** Export the document to this workspace STL, then slice it. */
        data class Export(val destination: File) : Decision

        /** Slice the STL the last Slice already exported. */
        data class Reuse(val source: File) : Decision
    }

    fun decide(document: CadDocument, exportsDirectory: File, nowMillis: Long): Decision {
        val existing = document.sliceStlPath?.let(::File)
        if (existing != null &&
            existing.isFile &&
            existing.length() > 0L &&
            document.sliceExportRevision == document.displayRevision
        ) {
            return Decision.Reuse(existing)
        }
        val destination = File(
            exportsDirectory,
            CadWorkspace.slug(document.name) + "-print-" + nowMillis + ".stl",
        )
        return Decision.Export(destination)
    }
}
