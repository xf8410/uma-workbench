package com.uma.workbench.ui

/** Immutable state used by the conversation history multi-select UI. */
data class ConversationSelectionState(
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val deleting: Boolean = false,
    val error: String? = null
) {
    val selectedCount: Int get() = selectedIds.size

    fun enter(initialId: String? = null) = copy(
        selecting = true,
        selectedIds = initialId?.let(::setOf) ?: emptySet(),
        error = null
    )

    fun toggle(id: String): ConversationSelectionState {
        val next = selectedIds.toMutableSet().apply { if (!add(id)) remove(id) }
        return copy(selecting = true, selectedIds = next, error = null)
    }

    fun selectAll(visibleIds: Collection<String>) = copy(
        selecting = true,
        selectedIds = visibleIds.toSet(),
        error = null
    )

    fun clearSelection() = copy(selectedIds = emptySet(), error = null)
    fun exit() = ConversationSelectionState()
}
