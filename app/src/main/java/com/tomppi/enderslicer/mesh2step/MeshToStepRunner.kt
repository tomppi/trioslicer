package com.tomppi.enderslicer.mesh2step

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs one engine process and turns its stdout lines into [MeshToStepProtocol.Event]s.
 *
 * This is the half of the seam that can be exercised without Android: it takes an arbitrary
 * command and environment, reads the JSON-lines protocol, keeps stderr as bounded log text,
 * enforces the wall-clock timeout and owns the cancel/kill path. [MeshToStepEngine] is the
 * layer that builds the app's Python command and the user-facing result.
 */
internal class MeshToStepRunner(
    private val command: List<String>,
    private val workingDirectory: File,
    private val environment: Map<String, String>,
    private val timeoutMillis: Long,
) : Closeable {

    internal data class Outcome(
        val exitCode: Int,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val result: JSONObject?,
        val error: MeshToStepProtocol.Event.Error?,
        val lastPhase: String,
        val log: List<String>,
    )

    private val processLock = Any()
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var process: Process? = null

    @Volatile
    private var cancelRequested = false

    /**
     * Starts the process, reads both streams to the end (or to the timeout/cancel) and
     * returns what happened. [onEvent] is called for every parsed protocol event, in order,
     * from the reader thread.
     */
    fun run(onEvent: (MeshToStepProtocol.Event) -> Unit): Outcome {
        val builder = ProcessBuilder(command)
        builder.directory(workingDirectory)
        builder.environment().putAll(environment)
        val started = try {
            builder.start()
        } catch (error: java.io.IOException) {
            throw MeshToStepEngine.ConversionException(
                "the mesh-to-STEP converter could not start: " +
                    (error.message ?: error.javaClass.simpleName),
                MeshToStepEngine.ConversionException.Kind.PROCESS_DIED,
                error,
            )
        }
        synchronized(processLock) {
            process = started
            if (cancelRequested) terminateLocked()
        }

        val result = AtomicReference<JSONObject?>()
        val errorEvent = AtomicReference<MeshToStepProtocol.Event.Error?>()
        val lastPhase = AtomicReference("starting")

        val stdoutThread = thread(name = "mesh2step-stdout", isDaemon = true) {
            runCatching {
                started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val event = MeshToStepProtocol.parse(line) ?: return@forEach
                        when (event) {
                            is MeshToStepProtocol.Event.Stage -> lastPhase.set(event.stage)
                            is MeshToStepProtocol.Event.Progress -> lastPhase.set(event.stage)
                            is MeshToStepProtocol.Event.Result -> result.set(event.data)
                            is MeshToStepProtocol.Event.Error -> errorEvent.compareAndSet(null, event)
                            is MeshToStepProtocol.Event.Malformed -> appendLog(event.line)
                            else -> {}
                        }
                        onEvent(event)
                    }
                }
            }
        }
        val stderrThread = thread(name = "mesh2step-stderr", isDaemon = true) {
            runCatching {
                started.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> if (line.isNotBlank()) appendLog(line) }
                }
            }
        }

        val finished = runCatching { started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        val timedOut = !finished && !cancelRequested
        if (!finished) {
            synchronized(processLock) { terminateLocked() }
        }
        stdoutThread.join(5_000)
        stderrThread.join(2_000)
        val exitCode = runCatching { started.exitValue() }.getOrDefault(-1)
        synchronized(processLock) { process = null }
        return Outcome(
            exitCode = exitCode,
            timedOut = timedOut,
            cancelled = cancelRequested,
            result = result.get(),
            error = errorEvent.get(),
            lastPhase = lastPhase.get(),
            log = logSnapshot(),
        )
    }

    /**
     * Kills the running process. Safe from any thread and safe to call twice; after this the
     * runner cannot be reused (the engine creates a new runner per conversion).
     */
    fun cancel() {
        synchronized(processLock) {
            cancelRequested = true
            terminateLocked()
        }
    }

    override fun close() {
        cancel()
        runCatching { process?.inputStream?.close() }
        runCatching { process?.errorStream?.close() }
    }

    private fun terminateLocked() {
        val running = process ?: return
        runCatching { running.destroy() }
        if (!runCatching { running.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { running.destroyForcibly() }
            runCatching { running.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun appendLog(line: String) {
        synchronized(log) {
            if (log.size < LOG_LIMIT) log.add(line)
        }
    }

    private fun logSnapshot(): List<String> = synchronized(log) { log.toList() }

    private companion object {
        /** Enough stderr for the engine's traceback and for a sentence; a chatty run costs no heap. */
        const val LOG_LIMIT = 400
    }
}
