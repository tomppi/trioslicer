package com.tomppi.enderslicer.solidcraft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The CAD design's one writer: the first edit of a burst is saved at once, and the last edit of
 * a burst is never dropped.
 *
 * The bug this pins: the old throttle launched its immediate save and left the job in a field
 * that was never cleared, so every later edit inside the 1.5 s window returned early and the
 * design file stayed at the first edit. A burst that then ended - the normal case for an agent
 * turn - left the document on disk behind the plate, and a restart lost the work.
 *
 * The interval is shortened for the test; the scheduling rule is the production one.
 */
class CadPersistSchedulerTest {

    private val writes = AtomicInteger()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun scheduler(intervalMillis: Long = 200L): CadPersistScheduler =
        CadPersistScheduler(
            intervalMillis = intervalMillis,
            scope = scope,
            write = { writes.incrementAndGet() },
            dispatcher = Dispatchers.Unconfined,
        )

    // The lambda goes last so "await { ... }" can be written as a trailing lambda: with
    // condition first, Kotlin binds the trailing lambda to the last parameter (timeoutMillis)
    // and refuses the call - which is exactly what the first full compile of this round found.
    private fun await(timeoutMillis: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("condition not reached in " + timeoutMillis + " ms", condition())
    }

    @Test
    fun theFirstEditIsWrittenAtOnce() {
        val scheduler = scheduler()
        scheduler.schedule()
        await { writes.get() == 1 }
        assertEquals(1, writes.get())
    }

    @Test
    fun anEditInsideTheWindowIsWrittenByTheTrailingSave() {
        val scheduler = scheduler()
        scheduler.schedule()
        await { writes.get() == 1 }
        // Inside the interval: the writer must not save at once, but the edit must not be lost.
        Thread.sleep(20)
        scheduler.schedule()
        Thread.sleep(20)
        assertEquals("the interval is not skipped", 1, writes.get())
        await { writes.get() == 2 }
        assertEquals("the trailing save lands the edit", 2, writes.get())
    }

    @Test
    fun theLastEditOfABurstStillReachesTheWriter() {
        val scheduler = scheduler(intervalMillis = 250L)
        scheduler.schedule()
        await { writes.get() == 1 }
        // A burst: three edits inside the interval. The first arrives while the interval is
        // closed, the rest while the trailing save is already scheduled.
        repeat(3) {
            Thread.sleep(5)
            scheduler.schedule()
        }
        await { writes.get() >= 2 }
        Thread.sleep(400)
        assertEquals("one trailing save, no dropped edit and no write storm", 2, writes.get())
    }

    /**
     * The race the single-threaded driver above cannot reach: the final edit of a burst
     * arriving while the drain loop is deciding it has written everything. The write models
     * the real one - it saves the LIVE state - so an edit is only ever lost when no write
     * starts after it at all, and that is what every round waits for.
     */
    @Test
    fun theTrailingSaveRunsWhenTheLastEditRacesTheDrainingLoop() {
        val edits = AtomicInteger()
        val saved = AtomicInteger()
        val scheduler = CadPersistScheduler(
            intervalMillis = 2L,
            scope = CoroutineScope(Dispatchers.Default),
            write = { saved.set(edits.get()) },
            dispatcher = Dispatchers.Default,
        )
        repeat(200) { round ->
            val start = CountDownLatch(1)
            val racers = (0 until 2).map {
                Thread {
                    start.await()
                    repeat(50) {
                        edits.incrementAndGet()
                        scheduler.schedule()
                    }
                }
            }
            racers.forEach { it.start() }
            start.countDown()
            racers.forEach { it.join() }
            val deadline = System.currentTimeMillis() + 2_000
            while (saved.get() != edits.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(1)
            }
            assertEquals(
                "round " + round + ": the trailing save lands the burst's last edit",
                edits.get(),
                saved.get(),
            )
        }
    }
}
