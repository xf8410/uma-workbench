package com.uma.workbench.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSelectionStateTest {
    @Test fun enterToggleSelectAllClearAndExitAreDeterministic() {
        var state = ConversationSelectionState().enter("one")
        assertTrue(state.selecting)
        assertEquals(setOf("one"), state.selectedIds)

        state = state.toggle("two")
        assertEquals(setOf("one", "two"), state.selectedIds)
        state = state.toggle("one")
        assertEquals(setOf("two"), state.selectedIds)

        state = state.selectAll(listOf("one", "two", "three"))
        assertEquals(3, state.selectedCount)
        state = state.clearSelection()
        assertTrue(state.selecting)
        assertTrue(state.selectedIds.isEmpty())

        state = state.exit()
        assertFalse(state.selecting)
        assertTrue(state.selectedIds.isEmpty())
    }
}
