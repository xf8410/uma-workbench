package com.uma.workbench.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLifecycleTest {
    private fun installed() = PluginLifecycle.install(validPluginManifest(), 100L)

    @Test fun installStartsDisabledUntilExplicitlyEnabled() {
        val plugin = installed()
        assertEquals(PluginLifecycleState.INSTALLED, plugin.state)
        val result = PluginLifecycle.enable(plugin, 200L) as PluginTransitionResult.Accepted
        assertEquals(PluginLifecycleState.ENABLED, result.plugin.state)
        assertEquals(200L, result.plugin.updatedAt)
    }

    @Test fun enableAndDisableAreIdempotent() {
        val enabled = (PluginLifecycle.enable(installed(), 200L) as PluginTransitionResult.Accepted).plugin
        val enabledAgain = PluginLifecycle.enable(enabled, 300L) as PluginTransitionResult.Accepted
        assertEquals(PluginLifecycleState.ENABLED, enabledAgain.plugin.state)
        val disabled = PluginLifecycle.disable(enabledAgain.plugin, 400L) as PluginTransitionResult.Accepted
        assertEquals(PluginLifecycleState.DISABLED, disabled.plugin.state)
        val disabledAgain = PluginLifecycle.disable(disabled.plugin, 500L) as PluginTransitionResult.Accepted
        assertEquals(PluginLifecycleState.DISABLED, disabledAgain.plugin.state)
    }

    @Test fun uninstallRequiresExplicitLifecycleTransition() {
        val result = PluginLifecycle.beginUninstall(installed(), 600L) as PluginTransitionResult.Accepted
        assertEquals(PluginLifecycleState.UNINSTALLING, result.plugin.state)
    }

    @Test fun invalidPluginCannotBeEnabled() {
        val invalid = installed().copy(state = PluginLifecycleState.INVALID)
        val result = PluginLifecycle.enable(invalid, 700L)
        assertTrue(result is PluginTransitionResult.Rejected)
    }
}
