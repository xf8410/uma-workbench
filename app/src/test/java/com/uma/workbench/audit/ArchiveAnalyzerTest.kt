package com.uma.workbench.audit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveAnalyzerTest {
    @Test fun inventoriesZipWithoutExtractingFiles() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("metadata/global-metadata.dat"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libil2cpp.so"))
            zip.write(ByteArray(10))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../escaped.txt"))
            zip.write(byteArrayOf(9))
            zip.closeEntry()
        }

        val result = ArchiveAnalyzer.analyze(ByteArrayInputStream(output.toByteArray()))

        assertEquals("ZIP", result.archiveFormat)
        assertEquals(3L, result.entryCount)
        assertEquals(3L, result.fileCount)
        assertEquals(15L, result.expandedBytes)
        assertEquals(1L, result.unsafePathCount)
        assertTrue(result.entryNamePreview.contains("metadata/global-metadata.dat"))
    }

    @Test fun detectsUnsafeArchivePaths() {
        assertTrue(ArchiveAnalyzer.isUnsafePath("../../outside"))
        assertTrue(ArchiveAnalyzer.isUnsafePath("/absolute/file"))
        assertTrue(ArchiveAnalyzer.isUnsafePath("C:/absolute/file"))
        assertFalse(ArchiveAnalyzer.isUnsafePath("safe/../inside/file"))
        assertFalse(ArchiveAnalyzer.isUnsafePath("lib/arm64-v8a/libil2cpp.so"))
    }
}
