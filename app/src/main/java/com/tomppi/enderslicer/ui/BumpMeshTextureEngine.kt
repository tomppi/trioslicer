package com.tomppi.enderslicer.ui

import android.content.Context
import com.tomppi.enderslicer.texturizer.BumpMeshStudio
import kotlinx.coroutines.CoroutineScope
import java.io.File

/*
 * The studio's seam to the native BumpMesh host.
 *
 * The host class (texturizer/BumpMeshStudio.kt) is owned by the engine lane; this interface is
 * the app-side shape the UI lane was given, kept in the ui package so the sheet, the ViewModel
 * and their tests can be written without the engine's WebView, resources or coroutine plumbing.
 * When the host lands, only [BumpMeshTextureEngines.create] needs a real implementation - the
 * rest of the studio calls this seam unchanged. The request/result field names follow the
 * shared contract: sourceStl, sourceTriangles, texture, mapping, tileMm, heightMm, smoothing,
 * refineMm, maxTriangles, mask, and stl/triangles/repairWarnings back.
 */

/** What the engine is asked to do; one instance per Apply press. */
internal data class BumpMeshApplyRequest(
    /** The selected object's unplaced mesh, written as a binary STL for the engine. */
    val sourceStl: File,
    val sourceTriangles: Int,
    val texture: BumpMeshTextureRef,
    val mapping: BumpMeshMappingMode,
    val tileMm: Float,
    val heightMm: Float,
    val smoothing: Float,
    val refineMm: Float,
    /** The tier's cap: the most triangles this apply may return. */
    val maxTriangles: Int,
    /**
     * Include-only painted region, one flag per source-mesh triangle, or null for the whole
     * object. Indices are the app's own triangle numbering; the host applies the page's
     * degenerate-triangle filter and renumbering before handing it to BumpMesh.
     */
    val mask: BooleanArray?,
)

/** The engine's answer: the textured mesh on disk and what it found in it. */
internal data class BumpMeshApplyResult(
    /** The exported binary STL, kept in the app's cache/workspace until it is imported. */
    val stl: File,
    val triangles: Int,
    /** BumpMesh's repair log (open edges, non-manifold edges, removed slivers), already phrased. */
    val repairWarnings: List<String> = emptyList(),
)

/**
 * The native host, as the studio sees it.
 *
 * [apply] may switch threads itself; [onProgress] may be called from any thread (the caller's
 * state holder is a StateFlow and updates are thread-safe). [cancel] asks the host to stop the
 * running apply; the coroutine still completes with a cancellation/failure the caller reports.
 */
internal interface BumpMeshTextureEngine {
    /** Pay the WebView/assets/model init once, while the sheet is open. */
    fun warmUp()

    /** The textures the host can actually texture with; empty while it is unavailable. */
    fun textures(): List<BumpMeshTextureRef> = emptyList()

    suspend fun apply(
        request: BumpMeshApplyRequest,
        onProgress: (phase: String, fraction: Float) -> Unit,
    ): BumpMeshApplyResult

    fun cancel()

    fun close()

    /**
     * Non-null while the host is not part of this build (the engine lane's class is not landed
     * yet, or the device has no WebView). The sheet shows this sentence and disables Apply
     * rather than pretending a press would work.
     */
    val unavailableReason: String? get() = null
}

/**
 * The build's engine. Until the engine lane ships texturizer/BumpMeshStudio.kt this returns
 * [UnavailableBumpMeshEngine]; wiring the real host replaces this one function body (the
 * contract in the task message is the seam, so the call sites do not change).
 */
internal object BumpMeshTextureEngines {
    fun create(context: Context, scope: CoroutineScope): BumpMeshTextureEngine {
        return runCatching { HostBumpMeshEngine(context, scope) }.getOrElse { UnavailableBumpMeshEngine() }
    }
}

/**
 * The real host: the engine lane's texturizer/BumpMeshStudio, adapted to the studio seam.
 *
 * The two shapes are deliberately field-for-field identical; this adapter is the only place
 * that names the host type, so a future contract change stops here. The gallery still prefers
 * the pinned 101-texture library manifest over the host's own scan for the asset list (see
 * BumpMeshTextureCatalog); [textures] is the host's own answer when the manifest is absent.
 */
private class HostBumpMeshEngine(
    context: Context,
    scope: CoroutineScope,
) : BumpMeshTextureEngine {
    private val host = BumpMeshStudio(context, scope)

    override fun warmUp() = host.warmUp()

    override fun textures(): List<BumpMeshTextureRef> =
        host.textures().map { BumpMeshTextureRef(it.assetPath, it.displayName) }

    override suspend fun apply(
        request: BumpMeshApplyRequest,
        onProgress: (phase: String, fraction: Float) -> Unit,
    ): BumpMeshApplyResult {
        val result = host.apply(
            BumpMeshStudio.ApplyRequest(
                sourceStl = request.sourceStl,
                sourceTriangles = request.sourceTriangles,
                texture = BumpMeshStudio.TextureRef(
                    assetPath = request.texture.assetPath,
                    displayName = request.texture.displayName,
                ),
                mapping = BumpMeshStudio.MappingMode.valueOf(request.mapping.name),
                tileMm = request.tileMm,
                heightMm = request.heightMm,
                smoothing = request.smoothing,
                refineMm = request.refineMm,
                maxTriangles = request.maxTriangles,
                mask = request.mask,
            ),
            onProgress = onProgress,
        )
        return BumpMeshApplyResult(
            stl = result.stl,
            triangles = result.triangles,
            repairWarnings = result.repairWarnings,
        )
    }

    override fun cancel() = host.cancel()

    override fun close() = host.close()
}

/** The honest placeholder while the host lane is still landing: it refuses by name. */
private class UnavailableBumpMeshEngine : BumpMeshTextureEngine {
    override fun warmUp() = Unit

    override suspend fun apply(
        request: BumpMeshApplyRequest,
        onProgress: (phase: String, fraction: Float) -> Unit,
    ): BumpMeshApplyResult = throw IllegalStateException(UNAVAILABLE)

    override fun cancel() = Unit

    override fun close() = Unit

    override val unavailableReason: String get() = UNAVAILABLE

    private companion object {
        const val UNAVAILABLE =
            "The native BumpMesh engine is not part of this build yet - the texture studio " +
                "waits for the engine lane's host to land."
    }
}
