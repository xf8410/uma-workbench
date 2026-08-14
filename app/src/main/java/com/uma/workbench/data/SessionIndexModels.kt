package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "session_records",
    primaryKeys = ["sourceId", "recordIndex"],
    indices = [Index(value = ["sourceId", "timestampMillis"]), Index(value = ["sourceId", "recordType"]), Index(value = ["timestampMillis"])]
)
data class SessionRecordEntity(
    val sourceId: String,
    val recordIndex: Long,
    val rawText: String,
    val timestampMillis: Long?,
    val recordType: String,
    val fieldCount: Int,
    val malformed: Boolean
)

@Entity(
    tableName = "session_fields",
    primaryKeys = ["sourceId", "recordIndex", "fieldPath"],
    indices = [Index(value = ["sourceId", "fieldPath"]), Index(value = ["fieldPath", "normalizedValue"])]
)
data class SessionFieldEntity(
    val sourceId: String,
    val recordIndex: Long,
    val fieldPath: String,
    val normalizedValue: String,
    val valueType: String,
    val truncated: Boolean
)
