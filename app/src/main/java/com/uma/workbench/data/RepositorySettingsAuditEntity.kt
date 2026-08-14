package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "github_settings_audit", indices = [Index("repository")])
data class RepositorySettingsAuditEntity(
    @PrimaryKey val id: String,
    val repository: String,
    val operation: String,
    val oldValue: String?,
    val newValue: String?,
    val confirmedAt: Long?,
    val verifiedAt: Long?,
    val result: String,
    val error: String? = null
)
