package com.uma.workbench.ui.viewers

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedDocumentReaderTest {
    @Test fun readsEveryByteAcrossPagesWithoutFullFileLoadOrTailLoss() {
        val complete = ByteArray(1_000_003) { index -> (index % 251).toByte() }
        var largestRequestedBuffer = 0
        val reader = PagedDocumentReader(
            open = { trackingStream(complete) { largestRequestedBuffer = maxOf(largestRequestedBuffer, it) } },
            size = { complete.size.toLong() },
            pageBytes = 64 * 1024
        )
        val restored = ArrayList<Byte>(complete.size)
        var offset = 0L
        while (true) {
            val page = reader.read(offset)
            page.bytes.forEach(restored::add)
            if (page.endReached) break
            offset = page.nextOffset
        }
        assertArrayEquals(complete, restored.toByteArray())
        assertTrue(largestRequestedBuffer <= 64 * 1024)
    }

    @Test fun exactPageBoundaryProducesACompleteLastPage() {
        val complete = ByteArray(128) { it.toByte() }
        val reader = PagedDocumentReader({ ByteArrayInputStream(complete) }, { 128L }, pageBytes = 64)
        val first = reader.read(0)
        val second = reader.read(first.nextOffset)
        assertFalse(first.endReached)
        assertTrue(second.endReached)
        assertEquals(64L, second.offset)
        assertArrayEquals(complete.copyOfRange(64, 128), second.bytes)
    }

    @Test fun unknownLengthStillDetectsShortTail() {
        val complete = ByteArray(70) { it.toByte() }
        val reader = PagedDocumentReader({ ByteArrayInputStream(complete) }, { null }, pageBytes = 64)
        assertFalse(reader.read(0).endReached)
        val tail = reader.read(64)
        assertTrue(tail.endReached)
        assertEquals(6, tail.bytes.size)
    }

    @Test fun skipFallbackWorksWhenProviderReportsZeroSkippedBytes() {
        val complete = ByteArray(100) { it.toByte() }
        val reader = PagedDocumentReader(
            open = {
                object : ByteArrayInputStream(complete) {
                    override fun skip(n: Long): Long = 0
                }
            },
            size = { complete.size.toLong() },
            pageBytes = 10
        )
        assertArrayEquals(complete.copyOfRange(40, 50), reader.read(40).bytes)
    }

    @Test fun binaryKindsUseRawHexMode() {
        listOf("libil2cpp.so", "game.apk", "archive.zip", "global-metadata.dat", "master.db", "data.sqlite").forEach {
            assertEquals(DocumentPageMode.HEX, DocumentPageRenderer.modeFor(it))
        }
        assertEquals(DocumentPageMode.TEXT, DocumentPageRenderer.modeFor("session.jsonl"))
    }

    private fun trackingStream(bytes: ByteArray, requested: (Int) -> Unit): InputStream = object : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            requested(length)
            return super.read(buffer, offset, length)
        }
    }
}
