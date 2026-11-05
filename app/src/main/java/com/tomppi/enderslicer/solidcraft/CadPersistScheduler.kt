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
    private var dirty = false

    /** The drain loop that owns the queue, by its own token; null when none does. */
    private var draining: Any? = null

    private var job: Job? = null

    /** Guards [dirty], [draining] and [job]: the schedule and exit decisions are atomic. */
    private val stateLock = Any()

    private val writeLock = Mutex()

    /**
     * Records that the document changed.
     *
     * [force] asks for an immediate save (a conversion, an open, a new design); it cancels the
     * waiting loop and starts a new one that saves without a delay. The write itself is
     * serialised, so a forced save cannot interleave with a trailing one.
     */
    fun schedule(force: Boolean = false) {
        synchronized(stateLock) {
            dirty = true
            if (force) {
                lastWriteAt = clock() - intervalMillis
                job?.cancel()
                draining = null
            }
            // The loop's exit decision and this claim are one critical section: an edit
            // arriving as the loop gives up either keeps it running or starts a fresh one,
            // so the trailing save of a burst always runs.
            if (draining != null) return
            val token = Any()
            draining = token
            val started = scope.launch(dispatcher) { run(token) }
            job = started
            started.invokeOnCompletion {
                synchronized(stateLock) { if (job === started) job = null }
            }
        }
    }

    private suspend fun run(token: Any) {
        try {
            while (true) {
                val last = lastWriteAt
                if (last != null) {
                    val wait = last + intervalMillis - clock()
                    if (wait > 0) delay(wait)
                }
                synchronized(stateLock) {
                    if (!dirty) {
                        if (draining === token) draining = null
                        return
                    }
                    dirty = false
                    lastWriteAt = clock()
                }
                writeLock.withLock { write() }
            }
        } finally {
            // A failed write (or a forced cancel) must not leave the queue owned, or every
            // later edit would be refused; the token keeps a replaced loop from taking the
            // queue away from the one that took over.
            synchronized(stateLock) { if (draining === token) draining = null }
        }
    }
}
