package com.tomppi.enderslicer.printer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream

/** The app's transport: a filesystem unix socket, through Android's LocalSocket. */
internal class LocalSocketTransport(private val path: String) : KlipperTransport {
    private var socket: LocalSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(timeoutMs: Int) {
        close()
        val s = LocalSocket()
        s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        // A read that never returns would leave a caller waiting for the life of the
        // process; a reply that does not come is an exception instead.
        s.soTimeout = timeoutMs
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun read(buffer: ByteArray): Int = input?.read(buffer) ?: -1

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Already gone: closing a socket that the other end dropped throws, and
            // there is nothing to do about it.
        }
        socket = null
        input = null
        output = null
    }
}
