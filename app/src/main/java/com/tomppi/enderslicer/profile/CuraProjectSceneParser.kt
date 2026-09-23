package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.ModelPlacement
import org.w3c.dom.Element
import java.io.InputStream

 data class CuraProjectScene(
    val modelName: String?,
    val affine: ModelPlacement.Affine3mf?,
    val dropToBuildPlate: Boolean,
    val objectCount: Int,
    val buildItemCount: Int,
    val componentCount: Int,
    val postProcessingScripts: String?,
    val warnings: List<String>,
)

object CuraProjectSceneParser {
    private const val MODEL_PATH = "3D/3dmodel.model"

    fun parse(input: InputStream): CuraProjectScene? {
        val entries = CuraArchive.readTextEntries(input, accept = { path ->
            path == MODEL_PATH || path.endsWith(".global.cfg")
        })
        val modelXml = entries[MODEL_PATH] ?: return null
        val document = SecureXml.factory().newDocumentBuilder()
            .parse(modelXml.byteInputStream(Charsets.UTF_8))
        document.documentElement.normalize()

        val objects = document.getElementsByTagNameNS("*", "object")
        val buildItems = document.getElementsByTagNameNS("*", "item")
        val components = document.getElementsByTagNameNS("*", "component")
        val objectElements = (0 until objects.length)
            .mapNotNull { objects.item(it) as? Element }
        val objectsById = objectElements.associateBy { it.getAttribute("id") }
        val firstItem = buildItems.item(0) as? Element
        val objectId = firstItem?.getAttribute("objectid")?.takeIf(String::isNotBlank)
        val rootObject = objectId?.let(objectsById::get) ?: objectElements.firstOrNull()

        val metadata = linkedMapOf<String, String>()
        rootObject?.getElementsByTagNameNS("*", "metadata")?.let { nodes ->
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as? Element ?: return@repeat
                val name = element.getAttribute("name")
                if (name.isNotBlank()) metadata[name] = element.textContent.trim()
            }
        }

        val warnings = mutableListOf<String>()
        if (buildItems.length > 1) {
            warnings += "Cura project contains ${buildItems.length} build items; multi-object placement is not implemented"
        }
        if (objects.length > 1 && components.length == 0) {
            warnings += "Cura project contains ${objects.length} objects; TrioSlicer currently applies only the first model transform"
        }

        val rootTransform = runCatching {
            parseOptionalTransform(firstItem?.getAttribute("transform").orEmpty())
        }.onFailure { warnings += "Cura object transform could not be parsed: ${it.message}" }
            .getOrNull()
        val resolvedObject = if (rootObject == null || rootTransform == null) {
            null
        } else {
            runCatching {
                resolveObject(
                    objectElement = rootObject,
                    objectsById = objectsById,
                    accumulated = rootTransform.affine,
                    hasExplicitTransform = rootTransform.explicit,
                    visited = linkedSetOf(),
                )
            }.onFailure { warnings += "Cura component transform could not be applied: ${it.message}" }
                .getOrNull()
        }
        val affine = resolvedObject
            ?.takeIf(ResolvedObject::hasExplicitTransform)
            ?.affine
            ?.withEmbeddedTargetBounds(resolvedObject.leafObject)
        val drop = metadata["cura:drop_to_buildplate"]?.equals("true", ignoreCase = true) == true
        val postProcessing = entries.entries
            .firstOrNull { it.key.endsWith(".global.cfg") }
            ?.value
            ?.let(::parsePostProcessingScripts)
        if (!postProcessing.isNullOrBlank()) {
            warnings += "Cura post-processing scripts are configured but are not executed by TrioSlicer"
        }

        return CuraProjectScene(
            modelName = rootObject?.getAttribute("name")?.takeIf(String::isNotBlank)
                ?: resolvedObject?.leafObject?.getAttribute("name")?.takeIf(String::isNotBlank),
            affine = affine,
            dropToBuildPlate = drop,
            objectCount = objects.length,
            buildItemCount = buildItems.length,
            componentCount = components.length,
            postProcessingScripts = postProcessing,
            warnings = warnings,
        )
    }

    private fun resolveObject(
        objectElement: Element,
        objectsById: Map<String, Element>,
        accumulated: ModelPlacement.Affine3mf,
        hasExplicitTransform: Boolean,
        visited: LinkedHashSet<String>,
    ): ResolvedObject {
        val id = objectElement.getAttribute("id").takeIf(String::isNotBlank)
            ?: error("component object has no id")
        check(visited.add(id)) { "component cycle detected at object $id" }
        try {
            val vertices = objectElement.getElementsByTagNameNS("*", "vertex")
            val componentNodes = objectElement.getElementsByTagNameNS("*", "component")
            check(!(vertices.length > 0 && componentNodes.length > 0)) {
                "object $id contains both a mesh and components"
            }
            if (vertices.length > 0) {
                return ResolvedObject(objectElement, accumulated, hasExplicitTransform)
            }
            check(componentNodes.length == 1) {
                if (componentNodes.length == 0) {
                    "object $id contains neither mesh vertices nor a component"
                } else {
                    "object $id contains ${componentNodes.length} components; multi-component composition is ambiguous"
                }
            }
            val component = componentNodes.item(0) as? Element
                ?: error("object $id contains an invalid component")
            val referencedId = component.getAttribute("objectid").takeIf(String::isNotBlank)
                ?: error("component in object $id has no objectid")
            val referencedObject = objectsById[referencedId]
                ?: error("component in object $id references missing object $referencedId")
            val componentTransform = parseOptionalTransform(component.getAttribute("transform"))
            return resolveObject(
                objectElement = referencedObject,
                objectsById = objectsById,
                accumulated = compose(accumulated, componentTransform.affine),
                hasExplicitTransform = hasExplicitTransform || componentTransform.explicit,
                visited = visited,
            )
        } finally {
            visited.remove(id)
        }
    }

    private fun parseOptionalTransform(raw: String): ParsedTransform {
        val trimmed = raw.trim()
        return if (trimmed.isBlank()) {
            ParsedTransform(identityAffine(), explicit = false)
        } else {
            ParsedTransform(parseTransform(trimmed), explicit = true)
        }
    }

    private fun parseTransform(raw: String): ModelPlacement.Affine3mf {
        val values = raw.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { token -> token.toDoubleOrNull() ?: error("invalid number '$token'") }
        require(values.size == 12) { "expected 12 values but found ${values.size}" }
        require(values.all(Double::isFinite)) { "transform contains a non-finite value" }

        return ModelPlacement.Affine3mf(
            linear = listOf(
                values[0], values[3], values[6],
                values[1], values[4], values[7],
                values[2], values[5], values[8],
            ),
            translationXmm = values[9],
            translationYmm = values[10],
            translationZmm = values[11],
        )
    }

    private fun compose(
        outer: ModelPlacement.Affine3mf,
        inner: ModelPlacement.Affine3mf,
    ): ModelPlacement.Affine3mf {
        val linear = multiply(outer.linear, inner.linear)
        return ModelPlacement.Affine3mf(
            linear = linear,
            translationXmm = transformX(
                outer.linear,
                inner.translationXmm,
                inner.translationYmm,
                inner.translationZmm,
            ) + outer.translationXmm,
            translationYmm = transformY(
                outer.linear,
                inner.translationXmm,
                inner.translationYmm,
                inner.translationZmm,
            ) + outer.translationYmm,
            translationZmm = transformZ(
                outer.linear,
                inner.translationXmm,
                inner.translationYmm,
                inner.translationZmm,
            ) + outer.translationZmm,
        )
    }

    private fun ModelPlacement.Affine3mf.withEmbeddedTargetBounds(
        objectElement: Element?,
    ): ModelPlacement.Affine3mf {
        val vertices = objectElement?.getElementsByTagNameNS("*", "vertex") ?: return this
        if (vertices.length == 0) return this

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        repeat(vertices.length) { index ->
            val vertex = vertices.item(index) as? Element ?: return@repeat
            val x = vertex.getAttribute("x").toDoubleOrNull() ?: error("invalid embedded vertex X")
            val y = vertex.getAttribute("y").toDoubleOrNull() ?: error("invalid embedded vertex Y")
            val z = vertex.getAttribute("z").toDoubleOrNull() ?: error("invalid embedded vertex Z")
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "embedded mesh contains a non-finite vertex" }

            val transformedX = transformX(linear, x, y, z) + translationXmm
            val transformedY = transformY(linear, x, y, z) + translationYmm
            val transformedZ = transformZ(linear, x, y, z) + translationZmm
            minX = minOf(minX, transformedX)
            maxX = maxOf(maxX, transformedX)
            minY = minOf(minY, transformedY)
            maxY = maxOf(maxY, transformedY)
            minZ = minOf(minZ, transformedZ)
        }

        return copy(
            targetCenterXmm = (minX + maxX) / 2.0,
            targetCenterYmm = (minY + maxY) / 2.0,
            targetBaseZmm = minZ,
        )
    }

    private fun multiply(a: List<Double>, b: List<Double>): List<Double> = List(9) { index ->
        val row = index / 3
        val column = index % 3
        a[row * 3] * b[column] +
            a[row * 3 + 1] * b[3 + column] +
            a[row * 3 + 2] * b[6 + column]
    }

    private fun transformX(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[0] * x + matrix[1] * y + matrix[2] * z

    private fun transformY(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[3] * x + matrix[4] * y + matrix[5] * z

    private fun transformZ(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[6] * x + matrix[7] * y + matrix[8] * z

    private fun identityAffine(): ModelPlacement.Affine3mf = ModelPlacement.Affine3mf(
        linear = ModelPlacement.IDENTITY,
        translationXmm = 0.0,
        translationYmm = 0.0,
        translationZmm = 0.0,
    )

    private fun parsePostProcessingScripts(globalCfg: String): String? {
        var inMetadata = false
        globalCfg.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                inMetadata = line.equals("[metadata]", ignoreCase = true)
            } else if (inMetadata && line.substringBefore('=', "").trim() == "post_processing_scripts") {
                return line.substringAfter('=', "").trim().takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private data class ParsedTransform(
        val affine: ModelPlacement.Affine3mf,
        val explicit: Boolean,
    )

    private data class ResolvedObject(
        val leafObject: Element,
        val affine: ModelPlacement.Affine3mf,
        val hasExplicitTransform: Boolean,
    )
}
