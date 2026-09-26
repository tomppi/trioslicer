package com.tomppi.enderslicer.printer

/**
 * The byte pipe klippy's API runs over.
 *
 * Split out of the client so its protocol can be exercised against a real klippy
 * without an Android device: the app supplies one backed by android.net.LocalSocket and
 * a test supplies one backed by java.nio, and both feed the same client. Every mistake
 * this client has made - the ETX framing, the method names, the shape of an update -
 * was found by running it against something, and this is what makes that possible off
 * the phone.
 */
internal interface KlipperTransport {
    /** Open the connection. Throws if it cannot be opened. */
    fun connect(timeoutMs: Int)

    /** Read some bytes into [buffer], or return -1 at the end of the stream. */
    fun read(buffer: ByteArray): Int

    /** Write all of [bytes]. */
    fun write(bytes: ByteArray)

    fun close()
}
