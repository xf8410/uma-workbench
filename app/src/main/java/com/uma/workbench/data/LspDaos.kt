package com.uma.workbench.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface LspServerDao {
    @Query("SELECT * FROM lsp_servers WHERE workspaceId = :wsId") fun observeAll(wsId: String): Flow<List<LspServerEntity>>
    @Query("SELECT * FROM lsp_servers WHERE workspaceId = :wsId AND serverId = :serverId LIMIT 1") suspend fun get(wsId: String, serverId: String): LspServerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(server: LspServerEntity)
    @Query("DELETE FROM lsp_servers WHERE workspaceId = :wsId AND serverId = :serverId") suspend fun delete(wsId: String, serverId: String)
}

@Dao interface LspDiagnosticDao {
    @Query("SELECT * FROM lsp_diagnostics WHERE workspaceId = :wsId AND fileUri = :uri ORDER BY startLine, startColumn") fun observeForFile(wsId: String, uri: String): Flow<List<LspDiagnosticEntity>>
    @Query("SELECT * FROM lsp_diagnostics WHERE workspaceId = :wsId ORDER BY startLine") fun observeAll(wsId: String): Flow<List<LspDiagnosticEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(diagnostics: List<LspDiagnosticEntity>)
    @Query("DELETE FROM lsp_diagnostics WHERE workspaceId = :wsId AND fileUri = :uri") suspend fun clearForFile(wsId: String, uri: String)
    @Query("DELETE FROM lsp_diagnostics WHERE workspaceId = :wsId") suspend fun clearAll(wsId: String)
}
