package com.tomppi.enderslicer.printer

/**
 * The pty pair the printer is bridged through.
 *
 * klippy opens a device node and applies termios to it, so it cannot be handed a
 * pipe: a fifo fails tcgetattr with ENOTTY. A pty is an ordinary serial port as far
 * as klippy is concerned. This process keeps [Pty.masterFd] and moves bytes between
 * it and the USB serial port; klippy is given [Pty.slavePath] in its configuration
 * and only ever sees that.
 *
 * The pair is created in native code because Android has no pty API. See
 * native/klipper-pty/klipper_pty.c for why that is, and for what the three libc
 * calls behind this do.
 */
object KlipperPty {
    init {
        System.loadLibrary("klipper_pty")
    }

    private external fun nativeOpenPty(fds: IntArray): String?

    /** A pty pair: the descriptor this process owns, and the path klippy opens. */
    class Pty(val masterFd: Int, val slavePath: String)

    /** Opens a pair, or returns null if the kernel would not give one. */
    fun open(): Pty? {
        val fds = IntArray(1)
        val slave = nativeOpenPty(fds) ?: return null
        return Pty(fds[0], slave)
    }
}
