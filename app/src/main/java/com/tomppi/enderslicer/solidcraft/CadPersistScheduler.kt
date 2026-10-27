package com.tomppi.enderslicer.solidcraft

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one writer for the live CAD document, at most once per [intervalMillis].
 *
 * The rule it enforces is the one the old two-branch throttle promised and could not keep: the
 * first edit of a burst is saved at once, and every edit that arrives inside the interval keeps
 * the writer dirty so one trailing save follows - the last edit of the burst is never dropped.
 *
 * A single loop rather than a job plus a trailing job: a job that never clears itself was the
 * defect this replaces (the first save left the field non-null, so every later edit inside the
 * window was skipped and the document on disk stayed at the first edit). The loop exits when it
 * has written everything it was told to; a save that starts after the last edit sees the whole
 * burst, because the write reads the live state.
 *
 * [clock] and [dispatcher] are injectable so a unit test can drive the scheduling without
 * sleeping through the real 1.5 s interval.
 */
internal class CadPersistScheduler(
    private val intervalMillis: Long,
    private val scope: CoroutineScope,
    private val write: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** The last write's start, in [clock]'s units; null until the first save. */
    @Volatile
    private var lastWriteAt: Long? = null

    /** True when something changed since the last write started (or the first one). */
    @Volatile
    private var dirty = false

    @Volatile
    private var job: Job? = null

    private val writeLock = Mutex()

    /**
     * Records that the document changed.
     *
     * [force] asks for an immediate save (a conversion, an open, a new design); it cancels the
     * waiting loop and starts a new one that saves without a delay. The write itself is
     * serialised, so a forced save cannot interleave with a trailing one.
     */
    fun schedule(force: Boolean = false) {
        dirty = true
        if (force) {
            lastWriteAt = clock() - intervalMillis
            job?.cancel()
        }
        if (job?.isActive == true) return
        val started = scope.launch(dispatcher) { run() }
        job = started
        started.invokeOnCompletion { if (job === started) job = null }
    }

    private suspend fun run() {
        while (true) {
            val last = lastWriteAt
            if (last != null) {
                val wait = last + intervalMillis - clock()
                if (wait > 0) delay(wait)
            }
            if (!dirty) return
            dirty = false
            lastWriteAt = clock()
            writeLock.withLock { write() }
        }
    }
}
