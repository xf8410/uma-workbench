package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "archive_entries",
    primaryKeys = ["sourceId", "entryIndex"],
    indices = [Index(value = ["sourceId", "path"]), Index(value = ["sourceId", "unsafePath"])]
)
data class ArchiveEntryEntity(
    val sourceId: String,
    val entryIndex: Long,
    val path: String,
    val directory: Boolean,
    val uncompressedBytes: Long,
    val compressedBytes: Long?,
    val crc32: Long?,
    val unsafePath: Boolean,
    val modifiedAt: Long?,
    val type: String
)
