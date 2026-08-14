package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "session_records",
    primaryKeys = ["sourceId", "recordIndex"],
    indices = [Index(value = ["sourceId", "timestampMillis"]), Index(value = ["sourceId", "recordType"])]
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
