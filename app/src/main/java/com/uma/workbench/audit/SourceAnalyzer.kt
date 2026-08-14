package com.uma.workbench.audit

import java.io.InputStream

class SourceAnalyzer {
    fun analyze(kind: SourceKind, input: InputStream): BinaryAnalysis? {
        if (kind == SourceKind.ARCHIVE) return ArchiveAnalyzer.analyze(input)
        val limit = when (kind) {
            SourceKind.SO -> 64
            SourceKind.SQLITE -> 100
            SourceKind.IL2CPP_METADATA -> IL2CPP_HEADER_PREVIEW_BYTES
            else -> return null
        }
        val header = input.readBounded(limit)
        return when (kind) {
            SourceKind.SO -> BinaryAnalyzers.analyzeElf(header)
            SourceKind.SQLITE -> BinaryAnalyzers.analyzeSqliteHeader(header)
            SourceKind.IL2CPP_METADATA -> BinaryAnalyzers.analyzeIl2CppMetadataHeader(header)
            else -> null
        }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val count = read(output, offset, limit - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return output.copyOf(offset)
    }

    private companion object {
        const val IL2CPP_HEADER_PREVIEW_BYTES = 8 + 8 * 31
    }
}
