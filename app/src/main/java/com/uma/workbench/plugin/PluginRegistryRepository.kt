package com.uma.workbench.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PluginRegistryRepository(
    private val dao: PluginRegistryDao,
    private val codec: PluginRegistryCodec = PluginRegistryCodec()
) {
    fun observeAll(): Flow<List<RegisteredPlugin>> = dao.observeAll().map { rows ->
        rows.mapNotNull { runCatching { codec.decode(it) }.getOrNull() }
    }

    suspend fun get(pluginId: String): RegisteredPlugin? =
        dao.get(pluginId)?.let { runCatching { codec.decode(it) }.getOrNull() }

    suspend fun install(
        manifest: PluginManifest,
        appVersion: Int,
        now: Long = System.currentTimeMillis()
    ): PluginValidationResult {
        val validation = PluginManifestValidator.validate(manifest, appVersion)
        if (validation.isValid) dao.upsert(codec.encode(PluginLifecycle.install(manifest, now)))
        return validation
    }

    suspend fun enable(pluginId: String, now: Long = System.currentTimeMillis()): PluginTransitionResult =
        transition(pluginId) { PluginLifecycle.enable(it, now) }

    suspend fun disable(pluginId: String, now: Long = System.currentTimeMillis()): PluginTransitionResult =
        transition(pluginId) { PluginLifecycle.disable(it, now) }

    suspend fun uninstall(pluginId: String, now: Long = System.currentTimeMillis()): PluginTransitionResult {
        val result = transition(pluginId) { PluginLifecycle.beginUninstall(it, now) }
        if (result is PluginTransitionResult.Accepted) dao.delete(pluginId)
        return result
    }

    private suspend fun transition(
        pluginId: String,
        block: (RegisteredPlugin) -> PluginTransitionResult
    ): PluginTransitionResult {
        val current = get(pluginId) ?: return PluginTransitionResult.Rejected("插件不存在")
        val result = block(current)
        if (result is PluginTransitionResult.Accepted) dao.upsert(codec.encode(result.plugin))
        return result
    }
}
