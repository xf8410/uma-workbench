package com.uma.workbench.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAnalyzersTest {
    @Test fun parsesLittleEndianElf64() {
        val bytes = ByteArray(64)
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1
        bytes[16] = 3; bytes[18] = 0xb7.toByte()
        val result = BinaryAnalyzers.analyzeElf(bytes)
        assertTrue(result.is64Bit)
        assertEquals(183, result.machine)
        assertEquals("little", result.endian)
    }

    @Test fun parsesSqliteHeader() {
        val bytes = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[16] = 0x10; bytes[17] = 0x00
        bytes[28] = 0; bytes[29] = 0; bytes[30] = 0; bytes[31] = 2
        bytes[56] = 0; bytes[57] = 0; bytes[58] = 0; bytes[59] = 1
        val result = BinaryAnalyzers.analyzeSqliteHeader(bytes)
        assertEquals(4096, result.pageSize)
        assertEquals(2, result.pageCount)
        assertEquals(1, result.textEncoding)
    }
}
