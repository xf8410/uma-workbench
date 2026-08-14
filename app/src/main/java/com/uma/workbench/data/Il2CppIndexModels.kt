package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "il2cpp_sections",
    primaryKeys = ["sourceId", "name"],
    indices = [Index("sourceId")]
)
data class Il2CppSectionEntity(
    val sourceId: String,
    val name: String,
    val offset: Long,
    val byteCount: Long,
    val metadataVersion: Int,
    val rangeValid: Boolean
)

@Entity(
    tableName = "il2cpp_section_chunks",
    primaryKeys = ["sourceId", "sectionName", "sectionOffset"],
    indices = [Index(value = ["sourceId", "sectionName"]), Index(value = ["sourceId", "absoluteOffset"])]
)
data class Il2CppSectionChunkEntity(
    val sourceId: String,
    val sectionName: String,
    val sectionOffset: Long,
    val absoluteOffset: Long,
    val byteCount: Int,
    val sha256: String
)

@Entity(
    tableName = "il2cpp_string_fragments",
    primaryKeys = ["sourceId", "offset"],
    indices = [Index("sourceId"), Index(value = ["sourceId", "text"])]
)
data class Il2CppStringFragmentEntity(
    val sourceId: String,
    val offset: Long,
    val byteCount: Int,
    val text: String,
    val continuesFromPrevious: Boolean,
    val continuesToNext: Boolean
)
