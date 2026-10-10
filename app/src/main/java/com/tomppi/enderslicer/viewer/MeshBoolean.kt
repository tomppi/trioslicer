package com.tomppi.enderslicer.viewer

import android.util.Log

/**
 * JNI bridge to the Manifold boolean engine (libmanifold_jni.so, arm64-v8a).
 *
 * Manifold (github.com/elalish/manifold, Apache-2.0, pinned v3.5.4) needs an
 * edge-manifold input: a mesh with a hole in it is refused, not mangled. The
 * shim is built and staged by `scripts/build-manifold-android.sh`; see
 * THIRD_PARTY_NOTICES.md for the licence.
 *
 * Every call fails soft. A boolean returns [Result.Failure] carrying the
 * engine's own word for what went wrong - usually "NotManifold", which is what
 * [MeshRepair] exists to fix - and never throws at a caller that cannot handle
 * it. [isManifold] answers false rather than throwing when the library is not
 * packaged for this ABI.
 *
 * The bridge is stateless: each call copies its meshes into the engine and
 * releases them again, so there is nothing to close and nothing to leak.
 */
object MeshBoolean {
    private const val TAG = "MeshBoolean"
    private const val LIBRARY = "manifold_jni"
    private const val NO_ERROR = "NoError"

    @Volatile
    private var loadState = 0

    @Volatile
    private var loaded = false

    /** Loads the engine library once; safe from any thread. */
    fun ensureLoaded(): Boolean {
        if (loadState != 0) return loaded
        synchronized(this) {
            if (loadState != 0) return loaded
            loadState = 1
            try {
                System.loadLibrary(LIBRARY)
                loaded = true
            } catch (error: UnsatisfiedLinkError) {
                loadState = 2
                Log.w(
                    TAG,
                    "libmanifold_jni.so unavailable in this ABI; mesh booleans disabled (" +
                        error.message + ")",
                )
            }
        }
        return loaded
    }

    /** False when the engine library is not packaged for this ABI. */
    val isAvailable: Boolean get() = loadState != 0 && loaded

    /** The engine's version, or null when the library is unavailable. */
    val engineVersion: String?
        get() = if (!ensureLoaded()) null else runCatching { nativeVersion() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Everything of [second] that is outside [first], added to [first].
     *
     * This is how a joint's beam and key become part of the half they are
     * rooted in: the beam overlaps that half, so the union welds it on.
     */
    fun union(first: StlMesh, second: StlMesh): Result = boolean(first, second, "union") { a, b -> nativeUnion(a, b) }

    /**
     * Everything of [second] removed from [first].
     *
     * This is how the matching pocket is cut out of the other half.
     */
    fun subtract(first: StlMesh, second: StlMesh): Result =
        boolean(first, second, "subtract") { a, b -> nativeSubtract(a, b) }

    /**
     * True when [mesh] is a closed solid the engine accepts: its own status is
     * NoError *and* an independent edge pairing finds every edge shared by
     * exactly two triangles. False when the library is unavailable, so a caller
     * can consult [isAvailable] to tell the two apart.
     */
    fun isManifold(mesh: StlMesh): Boolean = manifoldStatus(mesh) == NO_ERROR

    /**
     * The engine's verdict on [mesh] - "NoError", "NotManifold",
     * "NonFiniteVertex", ... - or null when the library is unavailable or the
     * mesh could not be handed over at all.
     */
    fun manifoldStatus(mesh: StlMesh): String? {
        if (!ensureLoaded()) return null
        var handle = 0L
        return try {
            handle = load(mesh) ?: return null
            nativeStatus(handle).takeIf { it.isNotBlank() }
        } catch (error: Throwable) {
            Log.w(TAG, "the engine could not take the mesh: " + (error.message ?: error.javaClass.simpleName))
            null
        } finally {
            release(handle)
        }
    }

    private fun boolean(first: StlMesh, second: StlMesh, name: String, operation: (Long, Long) -> Long): Result {
        if (!ensureLoaded()) return Result.Failure("libmanifold_jni.so is not available on this device")
        if (first.triangleCount <= 0 || second.triangleCount <= 0) {
            return Result.Failure("$name needs two meshes with geometry")
        }
        var firstHandle = 0L
        var secondHandle = 0L
        var resultHandle = 0L
        return try {
            firstHandle = load(first) ?: return Result.Failure("$name could not hand over the first mesh")
            secondHandle = load(second) ?: return Result.Failure("$name could not hand over the second mesh")
            val firstStatus = nativeStatus(firstHandle)
            if (firstStatus != NO_ERROR) return Result.Failure("$name refused the first mesh: $firstStatus")
            val secondStatus = nativeStatus(secondHandle)
            if (secondStatus != NO_ERROR) return Result.Failure("$name refused the second mesh: $secondStatus")

            val startedAt = System.nanoTime()
            resultHandle = operation(firstHandle, secondHandle)
            val millis = (System.nanoTime() - startedAt) / 1_000_000.0
            if (resultHandle == 0L) return Result.Failure("$name produced nothing")

            val status = nativeStatus(resultHandle)
            if (status != NO_ERROR) return Result.Failure("$name returned $status")
            val soup = nativeReadMesh(resultHandle)
                ?: return Result.Failure("$name produced geometry that could not be read back")
            Result.Success(
                mesh = meshFromTriangleSoup("$name: " + first.displayName, soup),
                status = status,
                closed = nativeIsClosed(resultHandle),
                volumeMm3 = nativeVolume(resultHandle),
                millis = millis,
            )
        } catch (error: Throwable) {
            Result.Failure("$name failed: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            release(resultHandle)
            release(secondHandle)
            release(firstHandle)
        }
    }

    /** Hands a mesh to the engine, or null when it cannot take it. */
    private fun load(mesh: StlMesh): Long? {
        val triangleCount = mesh.triangleCount
        if (triangleCount <= 0) return null
        val interleaved = mesh.interleavedVertices.toFloatArray()
        if (interleaved.size != triangleCount * MeshSolidBuilder.FLOATS_PER_TRIANGLE) return null
        val handle = nativeLoadMesh(interleaved, triangleCount)
        return handle.takeIf { it != 0L }
    }

    private fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    /** What a boolean call produced: the solid, or the engine's reason for refusing. */
    sealed interface Result {
        data class Success(
            val mesh: StlMesh,
            /** The engine's status, always "NoError" here. */
            val status: String,
            /** The independent edge-pairing check on the result. */
            val closed: Boolean,
            val volumeMm3: Double,
            /** The engine's own time for the operation. */
            val millis: Double,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    // The names and signatures here must match native/manifold-jni/manifold_jni.cpp.
    // The @JvmName pins the JVM symbol: Kotlin would otherwise suffix an
    // internal declaration with the module's name ("nativeBox$app"), and JNI
    // looks the symbol up by name.

    @JvmName("nativeVersion")
    internal external fun nativeVersion(): String

    @JvmName("nativeLoadMesh")
    internal external fun nativeLoadMesh(interleaved: FloatArray, triangleCount: Int): Long

    @JvmName("nativeBox")
    internal external fun nativeBox(low: FloatArray, high: FloatArray): Long

    @JvmName("nativeUnion")
    internal external fun nativeUnion(first: Long, second: Long): Long

    @JvmName("nativeSubtract")
    internal external fun nativeSubtract(first: Long, second: Long): Long

    @JvmName("nativeStatus")
    internal external fun nativeStatus(handle: Long): String

    @JvmName("nativeIsClosed")
    internal external fun nativeIsClosed(handle: Long): Boolean

    @JvmName("nativeReadMesh")
    internal external fun nativeReadMesh(handle: Long): FloatArray?

    @JvmName("nativeVolume")
    internal external fun nativeVolume(handle: Long): Double

    @JvmName("nativeLiveHandles")
    internal external fun nativeLiveHandles(): Int

    @JvmName("nativeRelease")
    internal external fun nativeRelease(handle: Long)
}

/**
 * A closed solid from a triangle soup: nine floats per triangle, positions
 * only, wound counter-clockwise seen from outside. The face normals and bounds
 * are computed here, so the result is an ordinary interleaved viewer mesh.
 */
internal fun meshFromTriangleSoup(displayName: String, soup: FloatArray): StlMesh {
    val builder = MeshSolidBuilder(displayName)
    val triangleCount = soup.size / 9
    for (triangle in 0 until triangleCount) {
        val at = triangle * 9
        builder.addTriangle(
            soup[at], soup[at + 1], soup[at + 2],
            soup[at + 3], soup[at + 4], soup[at + 5],
            soup[at + 6], soup[at + 7], soup[at + 8],
        )
    }
    return builder.build()
}
