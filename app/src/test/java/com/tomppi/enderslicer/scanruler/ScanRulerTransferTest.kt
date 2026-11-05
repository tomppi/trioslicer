package com.tomppi.enderslicer.scanruler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The export byte accounting behind the ScanRuler download bridge: declared size, ordered
 * chunks, the cap, the exact finish, cancellation and failure sentences. Android and WebView
 * are not involved, so these are the rules the activity can rely on.
 */
class ScanRulerTransferTest {

    private fun transfer(maxBytes: Long = 1024L, maxChunk: Int = 8) =
        ScanRulerTransfer(maxBytes, maxChunk)

    @Test
    fun beginAcceptsARealExportAndSuggestsItsSafeName() {
        val transfer = transfer()
        val begin = transfer.begin("bracket\\export/part.step", 100.0)
        assertTrue(begin.accepted)
        assertEquals("part.step", begin.suggestedName)
        assertEquals("part.step", transfer.fileName)
        assertEquals(100L, transfer.expectedBytes)
        assertTrue(transfer.waitingForDestination)

        val unnamed = transfer()
        assertEquals("scanruler-export", unnamed.begin("", 10.0).suggestedName)
    }

    @Test
    fun anotherExportCannotStartWhileOneIsInFlight() {
        val transfer = transfer()
        assertTrue(transfer.begin("one.step", 10.0).accepted)
        assertFalse(transfer.begin("two.step", 10.0).accepted)
        assertTrue(transfer.begin("one.step", 10.0).message.orEmpty().contains("already being written"))

        val writing = transfer()
        assertTrue(writing.begin("one.step", 10.0).accepted)
        assertTrue(writing.attach(ByteArrayOutputStream()))
        assertFalse(writing.begin("two.step", 10.0).accepted)
    }

    @Test
    fun aRefusedBeginLeavesTheInFlightExportUntouched() {
        // The activity refuses a second begin at the door. The export in flight must be
        // exactly as it was - same state, same name, same bytes - and able to finish:
        // routing the refusal through the shared complete() used to delete its destination
        // and clear the page's pending stream, wedging the bridge for the session.
        val transfer = transfer()
        assertTrue(transfer.begin("one.step", 6.0).accepted)
        val output = ByteArrayOutputStream()
        assertTrue(transfer.attach(output))
        assertTrue(transfer.append(byteArrayOf(1, 2, 3, 4)))

        val refused = transfer.begin("two.step", 10.0)
        assertFalse(refused.accepted)
        assertTrue(refused.message.orEmpty().contains("already being written"))
        assertEquals(ScanRulerTransfer.State.WRITING, transfer.state)
        assertEquals("one.step", transfer.fileName)
        assertEquals(4L, transfer.writtenBytes)

        assertTrue("the in-flight export keeps writing", transfer.append(byteArrayOf(5, 6)))
        assertTrue(transfer.finish())
        assertEquals(ScanRulerTransfer.State.DONE, transfer.state)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output.toByteArray().toList())
    }

    @Test
    fun zeroHugeAndNonFiniteSizesAreRefusedBeforeThePicker() {
        assertFalse(transfer().begin("a.step", 0.0).accepted)
        assertFalse(transfer().begin("a.step", -1.0).accepted)
        assertFalse(transfer().begin("a.step", Double.NaN).accepted)
        assertFalse(transfer().begin("a.step", Double.POSITIVE_INFINITY).accepted)
        val huge = transfer(maxBytes = 100L).begin("a.step", 101.0)
        assertFalse(huge.accepted)
        assertTrue(huge.message.orEmpty().contains("too large"))
    }

    @Test
    fun chunksNeedADestinationAndWriteInOrder() {
        val transfer = transfer()
        assertFalse("no stream yet, so no write is accepted", transfer.append(byteArrayOf(1)))

        assertTrue(transfer.begin("a.step", 6.0).accepted)
        val output = ByteArrayOutputStream()
        assertTrue(transfer.attach(output))
        assertTrue(transfer.writing)
        assertTrue(transfer.append(byteArrayOf(1, 2, 3)))
        assertTrue(transfer.append(byteArrayOf(4, 5, 6)))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output.toByteArray().toList())
        assertEquals(6L, transfer.writtenBytes)
    }

    @Test
    fun aChunkOverTheBoundOrBeforeTheStreamFailsWithASentence() {
        val oversized = transfer(maxChunk = 4)
        assertTrue(oversized.begin("a.step", 10.0).accepted)
        assertTrue(oversized.attach(ByteArrayOutputStream()))
        assertFalse(oversized.append(ByteArray(5)))
        assertTrue(oversized.failureMessage.orEmpty().contains("chunk that is too large"))

        val empty = transfer()
        assertTrue(empty.begin("a.step", 10.0).accepted)
        assertTrue(empty.attach(ByteArrayOutputStream()))
        assertFalse(empty.append(ByteArray(0)))
        assertTrue(empty.failureMessage.orEmpty().contains("empty chunk"))
    }

    @Test
    fun writingMoreThanDeclaredFails() {
        val transfer = transfer()
        assertTrue(transfer.begin("a.step", 4.0).accepted)
        assertTrue(transfer.attach(ByteArrayOutputStream()))
        assertTrue(transfer.append(ByteArray(4)))
        assertFalse(transfer.append(byteArrayOf(1)))
        assertTrue(transfer.failureMessage.orEmpty().contains("more bytes than it declared"))
    }

    @Test
    fun finishRequiresEveryDeclaredByteAndClosesTheStream() {
        val transfer = transfer()
        assertTrue(transfer.begin("a.step", 3.0).accepted)
        val output = ByteArrayOutputStream()
        assertTrue(transfer.attach(output))
        assertTrue(transfer.append(byteArrayOf(7, 8, 9)))
        assertTrue(transfer.finish())
        assertEquals(ScanRulerTransfer.State.DONE, transfer.state)
        assertEquals(3L, transfer.writtenBytes)

        val short = transfer()
        assertTrue(short.begin("a.step", 5.0).accepted)
        assertTrue(short.attach(ByteArrayOutputStream()))
        assertTrue(short.append(byteArrayOf(1, 2)))
        assertFalse(short.finish())
        assertTrue(short.failureMessage.orEmpty().contains("did not finish"))
    }

    @Test
    fun cancelAbandonsTheTransferAndKeepsItsSentence() {
        val transfer = transfer()
        assertTrue(transfer.begin("a.step", 10.0).accepted)
        assertTrue(transfer.attach(ByteArrayOutputStream()))
        transfer.cancel()
        assertEquals(ScanRulerTransfer.State.CANCELLED, transfer.state)
        assertNull(transfer.failureMessage)
        assertFalse(transfer.append(byteArrayOf(1)))
    }

    @Test
    fun failKeepsTheUserFacingSentence() {
        val transfer = transfer()
        assertTrue(transfer.begin("a.step", 10.0).accepted)
        transfer.fail("The destination went away, so no file was written.")
        assertEquals(ScanRulerTransfer.State.FAILED, transfer.state)
        assertEquals(
            "The destination went away, so no file was written.",
            transfer.failureMessage,
        )
    }
}
