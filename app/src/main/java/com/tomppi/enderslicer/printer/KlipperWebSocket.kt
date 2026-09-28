package com.tomppi.enderslicer.printer

import java.security.SecureRandom
import java.util.Base64

/**
 * The WebSocket framing a remote Klipper host needs, and nothing else.
 *
 * The client talks to klippy over a stream of JSON messages terminated with ETX, and Moonraker
 * speaks the same JSON-RPC over WebSocket frames instead. Rather than teach the client two
 * framings, the remote transport presents the stream it already understands: frames in, ETX out,
 * and the other way round. What is left here is the framing itself - RFC 6455, the parts a client
 * needs: a handshake, masked client frames, and reassembly of what a socket read happens to
 * deliver.
 *
 * Deliberately small: no extensions, no compression, no subprotocols. A byte pipe is all this is
 * used for.
 */
internal object KlipperWebSocket {
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    /** One frame as it arrived, before anything is made of it. */
    data class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean)

    private val random = SecureRandom()

    /**
     * A client frame.
     *
     * Masked, because the specification requires every client frame to be and Moonraker, being a
     * server, is entitled to drop one that is not. The mask is a parameter so that a test can
     * predict the bytes.
     */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray = randomMask()): ByteArray {
        val header = mutableListOf<Byte>()
        header += (0x80 or (opcode and 0x0F)).toByte() // FIN, and the opcode
        when {
            payload.size < 126 -> header += (0x80 or payload.size).toByte()
            payload.size < 65536 -> {
                header += (0x80 or 126).toByte()
                header += ((payload.size shr 8) and 0xFF).toByte()
                header += (payload.size and 0xFF).toByte()
            }
            else -> {
                header += (0x80 or 127).toByte()
                for (shift in 56 downTo 0 step 8) {
                    header += ((payload.size.toLong() shr shift) and 0xFF).toByte()
                }
            }
        }
        header += mask.toList()
        val masked = ByteArray(payload.size)
        for (index in payload.indices) {
            masked[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        return header.toByteArray() + masked
    }

    /**
     * Complete frames in [buffer], and whatever is left of a partial one.
     *
     * A socket read lands wherever it lands, so a frame can arrive in pieces and two can arrive
     * together; the remainder is returned for the next read, which is the whole point of this
     * shape. A frame that lies about its length is left alone rather than thrown, because the
     * connection is usually still good and an unterminated one is not worth guessing at.
     */
    fun decode(buffer: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = mutableListOf<Frame>()
        var rest = buffer
        while (true) {
            if (rest.size < 2) break
            val first = rest[0].toInt() and 0xFF
            val second = rest[1].toInt() and 0xFF
            val fin = (first and 0x80) != 0
            val opcode = first and 0x0F
            val masked = (second and 0x80) != 0
            var length = (second and 0x7F).toLong()
            var at = 2
            if (length == 126L) {
                if (rest.size < 4) break
                length = (((rest[2].toInt() and 0xFF) shl 8) or (rest[3].toInt() and 0xFF)).toLong()
                at = 4
            } else if (length == 127L) {
                if (rest.size < 10) break
                length = 0
                for (index in 2..9) length = (length shl 8) or (rest[index].toLong() and 0xFF)
                at = 10
            }
            val mask = if (masked) {
                if (rest.size < at + 4) break
                rest.copyOfRange(at, at + 4).also { at += 4 }
            } else {
                null
            }
            if (length < 0 || length > MAX_FRAME_BYTES) break
            if (rest.size < at + length) break
            val payload = rest.copyOfRange(at, at + length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                }
            }
            frames += Frame(opcode, payload, fin)
            rest = rest.copyOfRange(at + length.toInt(), rest.size)
        }
        return frames to rest
    }

    /** The request line and headers that turn an HTTP connection into a WebSocket one. */
    fun handshakeRequest(host: String, port: Int, path: String, apiKey: String?): String {
        val key = Base64.getEncoder().encodeToString(randomMask())
        return buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (!apiKey.isNullOrBlank()) append("X-Api-Key: ").append(apiKey).append("\r\n")
            append("\r\n")
        }
    }

    /** What the server must answer with for the handshake to be genuine. */
    fun handshakeAccept(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val hash = digest.digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(hash)
    }

    private fun randomMask(): ByteArray = ByteArray(4).also { random.nextBytes(it) }

    const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** A frame larger than this is not something a printer control channel should be sent. */
    private const val MAX_FRAME_BYTES = 8L * 1024 * 1024
}
