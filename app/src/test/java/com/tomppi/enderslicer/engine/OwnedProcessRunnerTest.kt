package com.tomppi.enderslicer.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class OwnedProcessRunnerTest {
    @Test
    fun processReturnedAfterCancellationIsDestroyedImmediately() {
        val process = FakeProcess()
        Thread.currentThread().interrupt()
        try {
            val error = runCatching {
                OwnedProcessRunner.run({ process }, 1, TimeUnit.SECONDS, shutdownGraceMillis = 0L)
            }.exceptionOrNull()

            assertTrue(error is InterruptedException)
            assertTrue(process.destroyed)
            assertFalse(process.isAlive)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun timeoutForciblyReapsAProcessThatIgnoresGracefulDestroy() {
        val process = FakeProcess(
            timedWaitResult = false,
            gracefulDestroyStops = false,
        )

        val error = runCatching {
            OwnedProcessRunner.run({ process }, 1, TimeUnit.MILLISECONDS, shutdownGraceMillis = 0L)
        }.exceptionOrNull()

        assertTrue(error is OwnedProcessRunner.ProcessTimeoutException)
        assertTrue(process.destroyed)
        assertTrue(process.forciblyDestroyed)
        assertFalse(process.isAlive)
    }

    @Test
    fun interruptedWaitDestroysAndReinterruptsTheOwnerThread() {
        val process = FakeProcess(interruptWait = true)
        try {
            val error = runCatching {
                OwnedProcessRunner.run({ process }, 1, TimeUnit.SECONDS, shutdownGraceMillis = 0L)
            }.exceptionOrNull()

            assertTrue(error is InterruptedException)
            assertTrue(process.destroyed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private class FakeProcess(
        private var alive: Boolean = true,
        private val timedWaitResult: Boolean = true,
        private val interruptWait: Boolean = false,
        private val gracefulDestroyStops: Boolean = true,
    ) : Process() {
        var destroyed: Boolean = false
            private set
        var forciblyDestroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (interruptWait) throw InterruptedException("cancelled")
            if (timedWaitResult) alive = false
            return timedWaitResult
        }
        override fun exitValue(): Int {
            check(!alive)
            return 0
        }
        override fun destroy() {
            destroyed = true
            if (gracefulDestroyStops) alive = false
        }
        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
