package com.uma.workbench.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPermissionDiffTest {
    @Test fun detectsAddedAndRemovedPermissions() {
        val old = validPluginManifest().copy(permissions = listOf("workspace:read", "network:old.example.com"))
        val next = old.copy(permissions = listOf("workspace:read", "network:new.example.com"))
        val diff = PluginPermissionDiff.between(old, next)
        assertEquals(listOf("network:new.example.com"), diff.added)
        assertEquals(listOf("network:old.example.com"), diff.removed)
        assertTrue(diff.requiresReapproval)
    }

    @Test fun unchangedPermissionsDoNotRequireReapproval() {
        val manifest = validPluginManifest()
        val diff = PluginPermissionDiff.between(manifest, manifest.copy(version = "1.2.4"))
        assertTrue(diff.isUnchanged)
        assertFalse(diff.requiresReapproval)
    }

    @Test fun updateWithNewPermissionRequiresApproval() {
        val current = PluginLifecycle.install(validPluginManifest(), 100L)
        val next = current.manifest.copy(
            version = "1.2.4",
            permissions = current.manifest.permissions + "network:extra.example.com"
        )
        val assessment = PluginUpdatePolicy.assess(current, next, 1)
        assertEquals(PluginUpdateDecision.REQUIRES_PERMISSION_REAPPROVAL, assessment.decision)
    }

    @Test fun invalidUpdateIsRejected() {
        val current = PluginLifecycle.install(validPluginManifest(), 100L)
        val next = current.manifest.copy(version = "not-a-version")
        val assessment = PluginUpdatePolicy.assess(current, next, 1)
        assertEquals(PluginUpdateDecision.REJECTED, assessment.decision)
    }
}
