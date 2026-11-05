package com.tomppi.enderslicer.agent.engine

import android.content.Context
import android.util.Log
import com.tomppi.enderslicer.nativebridge.BlenderEngine
import com.tomppi.enderslicer.nativebridge.CadEngine
import com.tomppi.enderslicer.scanruler.engine.ScanRulerEmbeddedSession
import com.tomppi.enderslicer.solidcraft.SolidCraftEngine
import java.io.File

/**
 * The unified engine MCP server's home: one place that builds the three surfaces, mints the
 * one token and keeps the server listening.
 *
 * The server starts at app start, like the engines' own doors used to - the loopback door is
 * the point, and **Reachable on the network** ([com.tomppi.enderslicer.agent.AgentBridges])
 * only decides whether the device's address also answers. The engines' own sockets on 9876
 * and 9877 remain private channels between the app and the engines; nothing hands them to an
 * agent any more.
 */
internal object EngineMcpHost {

    private const val TAG = "EngineMcpHost"

    private val lock = Any()

    @Volatile
    private var server: EngineMcpServer? = null

    private val surfaces = java.util.concurrent.ConcurrentHashMap<String, EngineToolSurface>()

    /** The ScanRuler workspace session, supplied by the view model once one exists. */
    @Volatile
    var scanRulerSession: () -> ScanRulerEmbeddedSession? = { null }

    /** One surface per engine, shared by the external server and the in-app agent. */
    fun surface(id: String): EngineToolSurface? = surfaces[id]

    /** Called at app start: mints the token and listens on loopback. Idempotent. */
    fun ensureStarted(context: Context): EngineMcpServer? = synchronized(lock) {
        server?.let { return it }
        val filesDir = context.applicationContext.filesDir
        val token = EngineMcpServer.ensureToken(filesDir)
        if (token.isNullOrEmpty()) {
            // Fail closed: a server without a token would accept anyone on the device.
            Log.e(TAG, "the engine server's token could not be written; the agent door stays closed")
            return null
        }
        val built = build(context.applicationContext, token)
        try {
            built.start()
        } catch (error: Throwable) {
            Log.e(TAG, "the engine MCP server could not listen: " + (error.message ?: error.javaClass.simpleName))
            runCatching { built.close() }
            return null
        }
        server = built
        built
    }

    private fun build(context: Context, token: String): EngineMcpServer {
        val filesDir = context.filesDir
        val solidcraft = SolidCraftToolSurface(
            forwarder = { SolidCraftEngine.externalForwarder() },
            onDocumentChanged = { delta -> SolidCraftEngine.onDocumentChanged?.invoke(delta) },
        )
        val cad = WireEngineSurface(
            id = "cad",
            displayName = "CAD engine",
            catalog = EngineCatalogs.cadTools(),
            rules = EngineCatalogs.reachAndRules("cad"),
            client = EngineWireClient(
                port = CadEngine.DEFAULT_PORT,
                token = { readToken(CadEngine.tokenFile(File(filesDir, "cad"))) },
            ),
        )
        val blender = WireEngineSurface(
            id = "blender",
            displayName = "Blender",
            catalog = EngineCatalogs.blenderTools(),
            rules = EngineCatalogs.reachAndRules("blender"),
            client = EngineWireClient(
                port = BlenderEngine.DEFAULT_MCP_PORT,
                token = { readToken(BlenderEngine.tokenFile(File(filesDir, "blender"))) },
            ),
        )
        val scanruler = ScanRulerToolSurface(session = { scanRulerSession() })
        listOf(solidcraft, cad, blender, scanruler).forEach { surfaces[it.id] = it }
        return EngineMcpServer(
            token = token,
            surfaces = listOf(solidcraft, cad, blender, scanruler),
            authorship = SolidCraftEngine.authorship(),
            log = { message -> Log.i(TAG, message) },
        ).also { created ->
            // The authorship's Take over closes the external clients it counted; the server
            // closes those connections and reports how many went.
            SolidCraftEngine.authorship().bindExternalCloser { created.closeAttachedConnections() }
        }
    }

    private fun readToken(file: File): String? =
        runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
