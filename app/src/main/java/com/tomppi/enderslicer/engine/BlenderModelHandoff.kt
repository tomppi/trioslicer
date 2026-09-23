package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Publishes the loaded model into the embedded Blender engine's import
 * directory (\`<filesDir>/blender/imports/\`), mirroring the export handoff the
 * engine already writes to (\`<filesDir>/blender/exports/\`).
 *
 * Nothing leaves the device: this is a local copy inside the app's own storage,
 * placed where the in-process Blender can open it. \`current.stl\` is the file the
 * Blender side reads; \`current.json\` describes what was published so a stale
 * handoff is recognisable.
 */
object BlenderModelHandoff {

    const val IMPORTS_DIR = "imports"
    const val CURRENT_MODEL = "current.stl"
    const val CURRENT_INFO = "current.json"

    fun importsDir(blenderRoot: File): File = File(blenderRoot, IMPORTS_DIR)

    fun currentModelFile(blenderRoot: File): File = File(importsDir(blenderRoot), CURRENT_MODEL)

    /**
     * Copies [source] to \`imports/current.stl\` and writes the sidecar, replacing any
     * previous handoff. The copy lands through a temporary file, so a reader never
     * observes a partial model.
     */
    fun publish(blenderRoot: File, source: File, sourceName: String = source.name): File {
        require(source.isFile && source.length() > 0L) {
            "The model to send to Blender is empty or missing"
        }
        val dir = importsDir(blenderRoot)
        require(dir.isDirectory || dir.mkdirs()) { "Cannot create " + dir.absolutePath }
        val target = File(dir, CURRENT_MODEL)
        val staging = File(dir, CURRENT_MODEL + ".part")
        source.copyTo(staging, overwrite = true)
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
        // Hand-built JSON: org.json is stubbed in JVM unit tests, and this keeps
        // the sidecar verifiable without pulling a JSON library into the app.
        val escapedName = sourceName.replace("\\", "\\\\").replace("\"", "\\\"")
        val info = "{" +
            "\"model\":\"" + CURRENT_MODEL + "\"," +
            "\"sourceName\":\"" + escapedName + "\"," +
            "\"bytes\":" + target.length() + "," +
            "\"sourceModified\":" + source.lastModified() + "," +
            "\"publishedAt\":" + System.currentTimeMillis() +
            "}"
        File(dir, CURRENT_INFO).writeText(info)
        return target
    }
}
