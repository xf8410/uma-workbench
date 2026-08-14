package com.uma.workbench.workspace

import android.content.Context
import com.uma.workbench.data.*
import java.util.UUID

/** Workspace lifecycle: create, open, recent, pin, archive, rename. Features 001-040. */
class WorkspaceManager(private val db: AppDatabase, private val context: Context) {

    suspend fun create(name: String, baseUri: String? = null): WorkspaceEntity {
        val now = System.currentTimeMillis()
        val ws = WorkspaceEntity(id = UUID.randomUUID().toString(), name = name, baseUri = baseUri ?: "", createdAt = now, updatedAt = now)
        db.workspaces().upsert(ws)
        return ws
    }

    suspend fun open(id: String) { db.workspaces().touchOpened(id, System.currentTimeMillis()) }

    suspend fun rename(id: String, name: String) { db.workspaces().rename(id, name, System.currentTimeMillis()) }

    suspend fun togglePin(id: String, pinned: Boolean) { db.workspaces().setPinned(id, pinned) }

    suspend fun archive(id: String) { db.workspaces().archive(id, System.currentTimeMillis()) }

    suspend fun addProject(workspaceId: String, name: String, sourceUri: String? = null, sourceType: String? = null, description: String? = null): ProjectEntity {
        val p = ProjectEntity(id = UUID.randomUUID().toString(), workspaceId = workspaceId, name = name, description = description, sourceUri = sourceUri, sourceType = sourceType, sortOrder = db.projects().list(workspaceId).size, createdAt = System.currentTimeMillis())
        db.projects().upsert(p)
        return p
    }

    suspend fun deleteProject(p: ProjectEntity) { db.projects().delete(p) }

    suspend fun recordRecentFile(workspaceId: String, uri: String, name: String) {
        val now = System.currentTimeMillis()
        db.recentFiles().upsert(RecentFileEntity(id = "$workspaceId:$uri", workspaceId = workspaceId, uri = uri, name = name, openedAt = now))
        db.recentFiles().trim(workspaceId)
    }

    suspend fun saveTabs(workspaceId: String, tabs: List<OpenTabEntity>) {
        db.openTabs().clear(workspaceId)
        if (tabs.isNotEmpty()) db.openTabs().upsertAll(tabs)
    }

    fun observeRecentFiles(workspaceId: String) = db.recentFiles().observe(workspaceId)
    fun observeProjects(workspaceId: String) = db.projects().observe(workspaceId)
    fun observeWorkspaces() = db.workspaces().observeAll()
}
