package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lsp_servers", indices = [Index("workspaceId"), Index("serverId")])
data class LspServerEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val serverId: String,
    val displayName: String,
    val language: String,
    val command: String,
    val argsJson: String,
    val initOptionsJson: String?,
    val enabled: Boolean = true,
    val status: String = "NOT_STARTED",
    val capabilitiesJson: String?,
    val updatedAt: Long
)

@Entity(tableName = "lsp_diagnostics", indices = [Index("workspaceId"), Index("fileUri")])
data class LspDiagnosticEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val fileUri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: String,
    val source: String?,
    val message: String,
    val code: String?,
    val updatedAt: Long
)
