package com.uma.workbench.knowledge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.KnowledgeEntryV2Entity
import com.uma.workbench.data.KnowledgeEvidenceRefEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class KnowledgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val database = app.database

    private val _entries = MutableStateFlow<List<KnowledgeEntryV2Entity>>(emptyList())
    val entries: StateFlow<List<KnowledgeEntryV2Entity>> = _entries.asStateFlow()

    private val _selectedEntry = MutableStateFlow<KnowledgeEntryV2Entity?>(null)
    val selectedEntry: StateFlow<KnowledgeEntryV2Entity?> = _selectedEntry.asStateFlow()

    private val _evidenceRefs = MutableStateFlow<List<KnowledgeEvidenceRefEntity>>(emptyList())
    val evidenceRefs: StateFlow<List<KnowledgeEvidenceRefEntity>> = _evidenceRefs.asStateFlow()

    fun loadEntries(workspaceId: String) {
        viewModelScope.launch {
            database.knowledgeEntriesV2().observe(workspaceId).collect { _entries.value = it }
        }
    }

    fun selectEntry(entry: KnowledgeEntryV2Entity?) {
        _selectedEntry.value = entry
        if (entry != null) {
            viewModelScope.launch {
                val refs = database.knowledgeEvidenceRefs().refs(entry.id)
                _evidenceRefs.value = refs
            }
        } else {
            _evidenceRefs.value = emptyList()
        }
    }

    fun createEntry(
        workspaceId: String,
        topic: String,
        category: String,
        conclusion: String,
        confidence: String = "CLUE",
        gameVersion: String? = null,
        sourceConversationId: String? = null,
        sourceMessageId: String? = null,
        openQuestions: String? = null
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            database.knowledgeEntriesV2().upsert(KnowledgeEntryV2Entity(
                id = id,
                workspaceId = workspaceId,
                topic = topic,
                category = category,
                conclusion = conclusion,
                confidence = confidence,
                gameVersion = gameVersion,
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId,
                openQuestions = openQuestions,
                status = "DRAFT",
                supersededBy = null,
                createdAt = now,
                updatedAt = now
            ))
        }
    }

    fun publishEntry(id: String) {
        viewModelScope.launch {
            val entry = _entries.value.firstOrNull { it.id == id } ?: return@launch
            database.knowledgeEntriesV2().upsert(entry.copy(status = "PUBLISHED", updatedAt = System.currentTimeMillis()))
        }
    }

    fun supersedeEntry(oldId: String, newEntry: KnowledgeEntryV2Entity) {
        viewModelScope.launch {
            database.knowledgeEntriesV2().upsert(newEntry)
            val old = _entries.value.firstOrNull { it.id == oldId } ?: return@launch
            database.knowledgeEntriesV2().upsert(old.copy(status = "SUPERSEDED", supersededBy = newEntry.id, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            database.knowledgeEvidenceRefs().deleteForEntry(id)
            database.knowledgeEntriesV2().delete(id)
        }
    }

    fun addEvidenceRef(knowledgeEntryId: String, evidenceArtifactId: String, relevance: String = "PRIMARY") {
        viewModelScope.launch {
            database.knowledgeEvidenceRefs().upsert(KnowledgeEvidenceRefEntity(
                id = UUID.randomUUID().toString(),
                knowledgeEntryId = knowledgeEntryId,
                evidenceArtifactId = evidenceArtifactId,
                relevance = relevance
            ))
        }
    }
}
