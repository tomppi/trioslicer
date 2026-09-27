package com.tomppi.enderslicer.printer

/** One line of the console, and which way it went. */
data class KlipperConsoleLine(
    val text: String,
    val source: Source,
    val atMillis: Long,
) {
    /** Where a line came from, which is the only thing that colours it. */
    enum class Source {
        /** A command this app sent. */
        SENT,

        /** What klippy said back, including the "// " comments its macros emit. */
        OUTPUT,

        /** klippy's own "!!" errors. */
        ERROR,
    }
}

/**
 * The console's scrollback: the last few hundred lines, and nothing older.
 *
 * Bounded on purpose. A print can emit output for hours - mesh probing, PID tuning and
 * every macro a start sequence runs all talk - and an unbounded list in a state object
 * the screen observes is a memory leak with a slow fuse.
 *
 * Locked rather than confined to a thread: lines arrive on the client's reader thread
 * while the screen reads the list on the main one, which is exactly the pair that races
 * without saying so.
 */
internal class KlipperConsole(private val capacity: Int = CAPACITY) {
    private val lines = ArrayDeque<KlipperConsoleLine>(capacity)
    private val lock = Any()

    /**
     * Add what klippy or the user said, as one line per line.
     *
     * A single response can carry several lines - a shutdown explains itself in five -
     * and a console that showed them joined would wrap them unpredictably.
     */
    /**
     * Add a line, and hand back the scrollback as it stands.
     *
     * The lock covers the snapshot as well as the append. It used to cover only the append, so
     * the caller assigned the snapshot to the flow outside it: the reader thread and a screen
     * thread could publish in the opposite order and the loser's older list won, which shows
     * as a line that has just been written not being there until the next one arrives.
     */
    fun add(text: String, source: KlipperConsoleLine.Source, atMillis: Long): List<KlipperConsoleLine> {
        val added = text.split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { KlipperConsoleLine(it, source, atMillis) }
        if (added.isEmpty()) return snapshot()
        synchronized(lock) {
            for (line in added) {
                if (lines.size >= capacity) lines.removeFirst()
                lines.addLast(line)
            }
        }
        return snapshot()
    }

    /** A copy of what is there now, safe to hold while more arrives. */
    fun snapshot(): List<KlipperConsoleLine> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    companion object {
        /** Roughly what fits in a scrollback a user will actually read back through. */
        const val CAPACITY = 400
    }
}
