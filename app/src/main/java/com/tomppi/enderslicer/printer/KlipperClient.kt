package com.tomppi.enderslicer.printer

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's side of klippy's API.
 *
 * klippy serves JSON over a unix socket when started with -a. It is the interface
 * Moonraker and the browser front ends use, and the one this app should use rather
 * than embedding any of them. Four details of it are easy to get wrong, and every one
 * of them was:
 *
 *  - Messages are terminated with ETX (0x03) in both directions, not with newlines.
 *    A request without it is buffered as incomplete and never answered, so a client
 *    written against newline framing connects, sends, and then reads nothing.
 *  - The method names are the socket's own, not the HTTP paths: info, objects/query,
 *    objects/subscribe, gcode/script, gcode/firmware_restart. printer.info is a path
 *    Moonraker translates, and asking for it here is answered with "No registered
 *    callback for path".
 *  - Replies echo the request id, and messages without one are klippy's own
 *    notifications. They arrive on the same stream in no particular order.
 *  - A notification can arrive at any time, not only while a request is outstanding,
 *    so one thread reads and dispatches while callers wait on their own reply.
 */
class KlipperClient internal constructor(
    private val transport: KlipperTransport,
    private val label: String,
) {
    /** The app's client, over a filesystem unix socket. */
    constructor(socketPath: String) : this(LocalSocketTransport(socketPath), socketPath)

    private val writeLock = Any()
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, SynchronousQueue<JSONObject>>()
    private var reader: Thread? = null
    private var pending = ByteArray(0)

    /** Everything klippy sends that is not a reply: notify_status_update and friends. */
    var onNotification: ((JSONObject) -> Unit)? = null

    val isConnected: Boolean
        get() = reader?.isAlive == true

    /** Connect and start reading. Throws if the socket is not there to be connected to. */
    fun connect(readTimeoutMs: Int = 15000) {
        close()
        transport.connect(readTimeoutMs)
        pending = ByteArray(0)
        reader = Thread({ readLoop() }, "klipper-api-reader").apply { isDaemon = true; start() }
        Log.i(TAG, "connected to $label")
    }

    fun close() {
        reader?.interrupt()
        reader = null
        transport.close()
        for (queue in waiting.values) queue.offer(ERROR_JSON)
        waiting.clear()
    }

    /**
     * One request, one reply. Blocking, so call it off the main thread.
     *
     * @throws KlipperError when klippy answers with an error object, which is how a
     *   bad method name or a rejected command comes back.
     */
    fun call(method: String, params: JSONObject? = null, timeoutMs: Long = 15000): JSONObject {
        val id = ids.getAndIncrement()
        val queue = SynchronousQueue<JSONObject>()
        waiting[id] = queue
        try {
            write(KlipperProtocol.encode(id, method, params))
            val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("no reply to $method")
            if (reply === ERROR_JSON) throw IllegalStateException("connection closed")
            if (reply.has("error")) throw KlipperError(reply.get("error").toString())
            return reply.optJSONObject("result") ?: JSONObject()
        } finally {
            waiting.remove(id)
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                // Every message in the batch, not just the first: one read can carry
                // several, and dropping the rest would lose an update silently.
                for (message in readMessages()) {
                    val queue = waiting[message.optInt("id", -1)]
                    if (queue != null) queue.offer(message) else onNotification?.invoke(message)
                }
            }
        } catch (e: Exception) {
            if (reader != null) Log.i(TAG, "read loop ended: ${e.message}")
        }
    }

    /** Read until at least one complete message has arrived, and return them all. */
    private fun readMessages(): List<JSONObject> {
        // Blocking reads, which is why this runs on its own thread.
        while (true) {
            val (messages, rest) = KlipperProtocol.decode(pending)
            pending = rest
            if (messages.isNotEmpty()) return messages
            val chunk = ByteArray(4096)
            val read = transport.read(chunk)
            if (read < 0) throw IllegalStateException("klippy closed the connection")
            pending += chunk.copyOfRange(0, read)
        }
    }

    /** Frame and send, on the one lock that keeps two callers' bytes apart. */
    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            transport.write(bytes)
        }
    }

    /** What klippy is: state, versions, and the message behind a shutdown. */
    fun info(): JSONObject = call("info")

    /** Current values of the named objects, e.g. extruder, heater_bed, toolhead. */
    fun query(vararg objects: String): JSONObject {
        val wanted = JSONObject()
        for (name in objects) wanted.put(name, JSONObject.NULL)
        return call("objects/query", JSONObject().put("objects", wanted))
            .optJSONObject("status") ?: JSONObject()
    }

    /**
     * Ask klippy to push these objects as they change, and return their status now.
     *
     * The reply carries the full status of everything asked for, which is why callers
     * should take this as their first snapshot rather than querying separately first:
     * a query followed by a subscribe leaves a gap between them, and a value that
     * changed in it would be neither in the snapshot nor pushed, so it would read
     * stale until it happened to change again.
     *
     * Later updates arrive as notify_status_update on [onNotification], carrying
     * {eventtime, status} - see [KlipperProtocol.statusUpdate].
     */
    fun subscribe(vararg objects: String): JSONObject {
        val wanted = JSONObject()
        for (name in objects) wanted.put(name, JSONObject.NULL)
        return call("objects/subscribe", JSONObject().put("objects", wanted))
            .optJSONObject("status") ?: JSONObject()
    }

    /**
     * Run G-code and wait for it to finish.
     *
     * klippy answers a script when the script completes, not when it is accepted, and
     * a G28 on this printer takes fifteen seconds - which is why the default request
     * timeout is wrong here and was the first thing to look like a failure.
     */
    fun gcode(script: String, timeoutMs: Long = 120000) =
        call("gcode/script", JSONObject().put("script", script), timeoutMs)

    /**
     * Run G-code without waiting for it to finish.
     *
     * For anything long - a print, a mesh calibration - where waiting would block a
     * caller for minutes or hours and the printer's own status is what reports
     * progress anyway.
     */
    fun gcodeAsync(script: String) {
        write(KlipperProtocol.encode(ids.getAndIncrement(), "gcode/script",
            JSONObject().put("script", script)))
    }

    /**
     * Reset the firmware, reload the config and restart the host software.
     *
     * This is the documented way out of a shutdown, including the one a crashed run
     * leaves behind: the micro-controller stays shut down until it is reset, and the
     * next start cannot configure it while it is.
     */
    fun firmwareRestart() = call("gcode/firmware_restart")

    class KlipperError(message: String) : Exception(message)

    private companion object {
        const val TAG = "KlipperClient"
        val ERROR_JSON = JSONObject().put("error", "closed")
    }
}
