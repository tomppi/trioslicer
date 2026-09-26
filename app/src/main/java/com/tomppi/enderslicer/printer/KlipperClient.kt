package com.tomppi.enderslicer.printer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
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
class KlipperClient(private val socketPath: String) {
    private val writeLock = Any()
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, SynchronousQueue<JSONObject>>()
    private var socket: LocalSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null
    private var pending = ByteArray(0)

    /** Everything klippy sends that is not a reply: notify_status_update and friends. */
    var onNotification: ((JSONObject) -> Unit)? = null

    val isConnected: Boolean
        get() = reader?.isAlive == true

    /** Connect and start reading. Throws if the socket is not there to be connected to. */
    fun connect(readTimeoutMs: Int = 15000) {
        close()
        val s = LocalSocket()
        s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        s.soTimeout = readTimeoutMs
        socket = s
        input = s.inputStream
        output = s.outputStream
        pending = ByteArray(0)
        reader = Thread({ readLoop() }, "klipper-api-reader").apply { isDaemon = true; start() }
        Log.i(TAG, "connected to $socketPath")
    }

    fun close() {
        reader?.interrupt()
        reader = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close: ${e.message}")
        }
        socket = null
        input = null
        output = null
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
            val request = JSONObject()
                .put("id", id)
                .put("method", method)
                .put("params", params ?: JSONObject())
            synchronized(writeLock) {
                val out = output ?: throw IllegalStateException("not connected")
                out.write(request.toString().toByteArray())
                out.write(ETX.toInt())          // OutputStream takes an int
                out.flush()
            }
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
                val message = readMessage()
                val queue = waiting[message.optInt("id", -1)]
                if (queue != null) queue.offer(message) else onNotification?.invoke(message)
            }
        } catch (e: Exception) {
            if (reader != null) Log.i(TAG, "read loop ended: ${e.message}")
        }
    }

    /** Read one ETX-terminated message, filling from the socket as needed. */
    private fun readMessage(): JSONObject {
        val stream = input ?: throw IllegalStateException("not connected")
        while (true) {
            val end = pending.indexOf(ETX)
            if (end >= 0) {
                val message = pending.copyOfRange(0, end)
                pending = pending.copyOfRange(end + 1, pending.size)
                return JSONObject(String(message))
            }
            val chunk = ByteArray(4096)
            val read = stream.read(chunk)
            if (read < 0) throw IllegalStateException("klippy closed the connection")
            pending += chunk.copyOfRange(0, read)
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
     * Ask klippy to push these objects as they change, rather than polling them.
     *
     * Updates arrive as notify_status_update on [onNotification]: the first parameter
     * is the changed objects, the second the event time.
     */
    fun subscribe(vararg objects: String) {
        val wanted = JSONObject()
        for (name in objects) wanted.put(name, JSONObject.NULL)
        call("objects/subscribe", JSONObject().put("objects", wanted))
    }

    /** Run G-code. */
    fun gcode(script: String) = call("gcode/script", JSONObject().put("script", script))

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
        const val ETX: Byte = 0x03
        val ERROR_JSON = JSONObject().put("error", "closed")
    }
}
