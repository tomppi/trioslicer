package com.tomppi.enderslicer.printer

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * A transport for tests: the same kind of socket the app uses, without Android.
 *
 * The reflective calls are not decoration. This compiles against the Android stub jar,
 * whose SocketChannel has no ProtocolFamily overload and which has no
 * UnixDomainSocketAddress at all; at run time it is a real JVM, which has both. That is
 * the whole trick that lets the app's own client be run against a real klippy from a
 * unit test.
 */
internal class JvmUnixSocketTransport(private val path: String) : KlipperTransport {
    private var channel: SocketChannel? = null

    override fun connect(timeoutMs: Int) {
        val familyType = Class.forName("java.net.ProtocolFamily")
        val family = Class.forName("java.net.StandardProtocolFamily").enumConstants
            .first { it.toString() == "UNIX" }
        val address = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", String::class.java)
            .invoke(null, path) as SocketAddress
        val opened = SocketChannel::class.java
            .getMethod("open", familyType)
            .invoke(null, family) as SocketChannel
        opened.connect(address)
        channel = opened
    }

    override fun read(buffer: ByteArray): Int {
        val ch = channel ?: return -1
        val n = ch.read(ByteBuffer.wrap(buffer))
        return if (n < 0) -1 else n
    }

    override fun write(bytes: ByteArray) {
        val ch = channel ?: throw IllegalStateException("not connected")
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) ch.write(buffer)
    }

    override fun close() {
        runCatching { channel?.close() }
        channel = null
    }
}
