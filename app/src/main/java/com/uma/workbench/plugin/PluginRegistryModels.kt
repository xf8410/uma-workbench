package com.uma.workbench.plugin

import kotlinx.serialization.Serializable

@Serializable
enum class PluginLifecycleState {
    INSTALLED,
    ENABLED,
    DISABLED,
    UPDATE_AVAILABLE,
    INVALID,
    UNINSTALLING
}

@Serializable
data class RegisteredPlugin(
    val manifest: PluginManifest,
    val state: PluginLifecycleState = PluginLifecycleState.INSTALLED,
    val installedAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    val grantedPermissions: List<String> = emptyList()
)

sealed class PluginTransitionResult {
    data class Accepted(val plugin: RegisteredPlugin) : PluginTransitionResult()
    data class Rejected(val reason: String) : PluginTransitionResult()
}

object PluginLifecycle {
    fun install(manifest: PluginManifest, now: Long): RegisteredPlugin = RegisteredPlugin(
        manifest = manifest,
        state = PluginLifecycleState.INSTALLED,
        installedAt = now,
        updatedAt = now,
        grantedPermissions = manifest.permissions
    )

    fun enable(plugin: RegisteredPlugin, now: Long): PluginTransitionResult = when (plugin.state) {
        PluginLifecycleState.INSTALLED,
        PluginLifecycleState.DISABLED,
        PluginLifecycleState.UPDATE_AVAILABLE -> PluginTransitionResult.Accepted(
            plugin.copy(state = PluginLifecycleState.ENABLED, updatedAt = now, lastError = null)
        )
        PluginLifecycleState.ENABLED -> PluginTransitionResult.Accepted(plugin)
        else -> PluginTransitionResult.Rejected("当前状态 ${plugin.state} 不允许启用")
    }

    fun disable(plugin: RegisteredPlugin, now: Long): PluginTransitionResult = when (plugin.state) {
        PluginLifecycleState.ENABLED -> PluginTransitionResult.Accepted(
            plugin.copy(state = PluginLifecycleState.DISABLED, updatedAt = now)
        )
        PluginLifecycleState.DISABLED,
        PluginLifecycleState.INSTALLED -> PluginTransitionResult.Accepted(plugin)
        else -> PluginTransitionResult.Rejected("当前状态 ${plugin.state} 不允许停用")
    }

    fun beginUninstall(plugin: RegisteredPlugin, now: Long): PluginTransitionResult = when (plugin.state) {
        PluginLifecycleState.UNINSTALLING -> PluginTransitionResult.Accepted(plugin)
        PluginLifecycleState.ENABLED,
        PluginLifecycleState.DISABLED,
        PluginLifecycleState.INSTALLED,
        PluginLifecycleState.UPDATE_AVAILABLE,
        PluginLifecycleState.INVALID -> PluginTransitionResult.Accepted(
            plugin.copy(state = PluginLifecycleState.UNINSTALLING, updatedAt = now)
        )
    }
}
