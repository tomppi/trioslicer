package com.tomppi.enderslicer.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.util.Locale

/*
 * The studio gallery's texture list and thumbnails.
 *
 * The list is the asset pipeline's pinned 101-texture catalogue (assets/bumpmesh/library, staged
 * by prepareBumpMeshAssets from the explicitly pinned CNCKitchen/stlTexturizer texture commit):
 * library/manifest.json carries the display name, category, image and thumbnail for every
 * texture, so this scan and the engine cannot disagree about what the bundle contains. The old
 * 24-preset folder is the fallback for a build that has not staged the library yet.
 *
 * Thumbnails prefer the catalogue's own 4 KB webp; a missing thumbnail falls back to decoding
 * the full texture downsampled. Lookups are lazy per cell, so the gallery pays for the images
 * it draws.
 */
internal object BumpMeshTextureCatalog {
    private const val LIBRARY_ROOT = "bumpmesh/library"
    private const val MANIFEST = LIBRARY_ROOT + "/manifest.json"
    private const val LEGACY_ROOT = "bumpmesh/textures"
    private const val LEGACY_THUMBS = LEGACY_ROOT + "/thumbs"
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** The catalogue: the pinned 101-texture library when present, else the legacy presets. */
    fun load(context: Context): List<BumpMeshTextureRef> =
        libraryTextures(context).ifEmpty { legacyTextures(context) }

    /** Reads the pipeline's manifest. A malformed/missing manifest is the legacy fallback. */
    private fun libraryTextures(context: Context): List<BumpMeshTextureRef> = runCatching {
        val text = context.assets.open(MANIFEST).bufferedReader().use { it.readText() }
        val manifest = JSONObject(text)
        val categoryByld = mutableMapOf<String, String>()
        val categories = manifest.optJSONArray("categories")
        if (categories != null) {
            for (index in 0 until categories.length()) {
                val category = categories.optJSONObject(index) ?: continue
                categoryByld[category.optString("id")] = category.optString("label")
            }
        }
        val textures = manifest.optJSONArray("textures") ?: return emptyList()
        buildList {
            for (index in 0 until textures.length()) {
                val entry = textures.optJSONObject(index) ?: continue
                val name = entry.optString("name").ifBlank { continue }
                val file = entry.optString("file").ifBlank { continue }
                val thumb = entry.optString("thumb")
                add(
                    BumpMeshTextureRef(
                        assetPath = LIBRARY_ROOT + "/" + file,
                        displayName = name,
                        categoryLabel = categoryByld[entry.optString("category")],
                        thumbnailAssetPath = if (thumb.isBlank()) null else LIBRARY_ROOT + "/" + thumb,
                    ),
                )
            }
        }.sortedBy { it.displayName.lowercase(Locale.US) }
    }.getOrDefault(emptyList())

    /** The pre-1.9.14 24-preset folder, unchanged: a build without the staged library still works. */
    private fun legacyTextures(context: Context): List<BumpMeshTextureRef> {
        val names = runCatching { context.assets.list(LEGACY_ROOT).orEmpty().toList() }
            .getOrDefault(emptyList())
        return names
            .filter { it.substringAfterLast('.', "").lowercase(Locale.US) in EXTENSIONS }
            .sorted()
            .map {
                BumpMeshTextureRef(
                    assetPath = LEGACY_ROOT + "/" + it,
                    displayName = displayNameOf(it),
                )
            }
    }

    /** Decodes one thumbnail off the UI thread; null when neither the thumb nor the image opens. */
    fun thumbnail(context: Context, texture: BumpMeshTextureRef): ImageBitmap? = runCatching {
        val options = BitmapFactory.Options()
        texture.thumbnailAssetPath?.let { thumb ->
            context.assets.open(thumb).use { stream ->
                return@runCatching BitmapFactory.decodeStream(stream, null, options)
                    ?.asImageBitmap()
            }
        }
        val legacyThumb = LEGACY_THUMBS + "/" + texture.fileStem
        val legacyNames = runCatching { context.assets.list(LEGACY_THUMBS).orEmpty().toSet() }
            .getOrDefault(emptySet())
        val legacyThumbFile = listOf("webp", "png", "jpg", "jpeg")
            .firstOrNull { legacyThumb + it in legacyNames }
        val path = if (legacyThumbFile != null) {
            legacyThumb + legacyThumbFile
        } else {
            texture.assetPath
        }
        if (legacyThumbFile == null) options.inSampleSize = 4
        context.assets.open(path).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }.getOrNull()

    /** "woodgrain_02.jpg" -> "Woodgrain 02"; the legacy presets carry no display names. */
    fun displayNameOf(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val words = stem
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .trim()
        if (words.isEmpty()) return stem
        return words.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word[0].uppercaseChar() + word.substring(1)
            }
    }
}
