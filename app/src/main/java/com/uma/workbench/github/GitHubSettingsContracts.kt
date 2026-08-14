package com.uma.workbench.github

/** Repository settings exposed by the GitHub workbench. */
data class RepositorySettings(
    val owner: String,
    val name: String,
    val visibility: RepositoryVisibility,
    val isArchived: Boolean,
    val defaultBranch: String,
    val allowIssues: Boolean,
    val allowProjects: Boolean,
    val allowWiki: Boolean,
    val allowDiscussions: Boolean,
    val allowActions: Boolean
)

enum class RepositoryVisibility { PUBLIC, PRIVATE, INTERNAL, UNKNOWN }

data class VisibilityChangePreview(
    val repository: String,
    val from: RepositoryVisibility,
    val to: RepositoryVisibility,
    val warnings: List<String>,
    val requiresExplicitConfirmation: Boolean = true
)

data class RepositorySettingsAudit(
    val id: String,
    val repository: String,
    val operation: String,
    val oldValue: String?,
    val newValue: String?,
    val confirmedAt: Long?,
    val verifiedAt: Long?,
    val result: String,
    val error: String? = null
)

interface GitHubRepositorySettingsGateway {
    suspend fun getSettings(owner: String, name: String): RepositorySettings
    suspend fun previewVisibilityChange(owner: String, name: String, target: RepositoryVisibility): VisibilityChangePreview
    /** Must only be called after an explicit user confirmation in the UI. */
    suspend fun changeVisibility(owner: String, name: String, target: RepositoryVisibility, confirmationId: String): RepositorySettings
}
