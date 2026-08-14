package com.uma.workbench.audit

import com.uma.workbench.data.Il2CppSectionChunkEntity
import com.uma.workbench.data.Il2CppStringFragmentEntity
import java.io.InputStream
import java.security.MessageDigest

/**
 * Indexes every IL2CPP metadata section without loading the source into memory. Each committed
 * chunk has a deterministic primary key and SHA-256, so replay after cancellation is idempotent.
 */
object Il2CppMetadataIndexer {
    data class Checkpoint(val sectionName: String, val nextOffset: Long) {
        fun encode(): String = "il2cpp:$sectionName:$nextOffset"

        companion object {
            fun decode(value: String?): Checkpoint? {
                if (value == null || !value.startsWith("il2cpp:")) return null
                val parts = value.split(':', limit = 3)
                if (parts.size != 3) return null
                return parts[2].toLongOrNull()?.let { Checkpoint(parts[1], it) }
            }
        }
    }

    data class Batch(
        val chunk: Il2CppSectionChunkEntity,
        val fragments: List<Il2CppStringFragmentEntity>,
        val checkpoint: Checkpoint?,
        val consumedBytes: Int,
        val complete: Boolean
    )

    val stringSectionNames = setOf("stringLiteralData", "string")

    fun validateSections(analysis: Il2CppMetadataAnalysis, sourceLength: Long?): List<Il2CppMetadataSection> =
        analysis.sections.filter { section ->
            section.byteCount == 0L || (
                section.offset >= 0 && section.byteCount >= 0 &&
                    section.offset <= Long.MAX_VALUE - section.byteCount &&
                    (sourceLength == null || section.offset + section.byteCount <= sourceLength)
                )
        }

    fun readSectionBatch(
        sourceId: String,
        openInput: () -> InputStream,
        section: Il2CppMetadataSection,
        nextOffset: Long = 0,
        maxBytes: Int = DEFAULT_BATCH_BYTES
    ): Batch {
        require(nextOffset in 0 until section.byteCount) { "Checkpoint is outside section ${section.name}" }
        val requested = minOf(maxBytes.toLong(), section.byteCount - nextOffset).toInt()
        val bytes = ByteArray(requested)
        val count = openInput().use { input ->
            input.skipFully(section.offset + nextOffset)
            input.readFullyOrEof(bytes)
        }
        require(count == requested) { "IL2CPP section ${section.name} is truncated at ${nextOffset + count}" }
        val complete = nextOffset + count == section.byteCount
        val fragments = if (section.name in stringSectionNames) parseNullTerminatedFragments(
            sourceId, bytes, section.offset + nextOffset,
            continuesFromPrevious = nextOffset > 0,
            continuesToNext = !complete
        ) else emptyList()
        return Batch(
            chunk = Il2CppSectionChunkEntity(
                sourceId = sourceId,
                sectionName = section.name,
                sectionOffset = nextOffset,
                absoluteOffset = section.offset + nextOffset,
                byteCount = count,
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            ),
            fragments = fragments,
            checkpoint = if (complete) null else Checkpoint(section.name, nextOffset + count),
            consumedBytes = count,
            complete = complete
        )
    }

    internal fun parseNullTerminatedFragments(
        sourceId: String,
        bytes: ByteArray,
        absoluteOffset: Long,
        continuesFromPrevious: Boolean,
        continuesToNext: Boolean
    ): List<Il2CppStringFragmentEntity> {
        val result = ArrayList<Il2CppStringFragmentEntity>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] != 0.toByte()) continue
            if (index > start) result += fragment(sourceId, bytes, start, index, absoluteOffset, continuesFromPrevious && start == 0, false)
            start = index + 1
        }
        if (start < bytes.size) result += fragment(sourceId, bytes, start, bytes.size, absoluteOffset, continuesFromPrevious && start == 0, continuesToNext)
        return result
    }

    private fun fragment(sourceId: String, bytes: ByteArray, start: Int, end: Int, absoluteOffset: Long, fromPrevious: Boolean, toNext: Boolean) =
        Il2CppStringFragmentEntity(sourceId, absoluteOffset + start, end - start, bytes.copyOfRange(start, end).toString(Charsets.UTF_8), fromPrevious, toNext)

    private fun InputStream.skipFully(byteCount: Long) {
        var remaining = byteCount
        val discard = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                val count = read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                require(count >= 0) { "Source ended before requested IL2CPP offset" }
                remaining -= count
            }
        }
    }

    private fun InputStream.readFullyOrEof(output: ByteArray): Int {
        var offset = 0
        while (offset < output.size) {
            val count = read(output, offset, output.size - offset)
            if (count < 0) break
            if (count > 0) offset += count
        }
        return offset
    }

    const val DEFAULT_BATCH_BYTES = 256 * 1024
}
