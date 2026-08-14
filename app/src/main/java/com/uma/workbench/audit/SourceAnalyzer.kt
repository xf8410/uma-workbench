package com.uma.workbench.audit

import java.io.InputStream

class SourceAnalyzer {
    fun analyze(kind: SourceKind, input: InputStream): BinaryAnalysis? {
        val limit = when (kind) { SourceKind.SO -> 64; SourceKind.SQLITE -> 100; else -> return null }
        val header = input.readBounded(limit)
        return when (kind) {
            SourceKind.SO -> BinaryAnalyzers.analyzeElf(header)
            SourceKind.SQLITE -> BinaryAnalyzers.analyzeSqliteHeader(header)
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
}
