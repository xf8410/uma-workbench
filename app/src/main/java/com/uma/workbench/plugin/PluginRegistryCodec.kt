package com.uma.workbench.plugin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PluginRegistryCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
) {
    fun encode(plugin: RegisteredPlugin): PluginRegistryEntity = PluginRegistryEntity(
        pluginId = plugin.manifest.id,
        manifestJson = json.encodeToString(plugin.manifest),
        state = plugin.state.name,
        installedAt = plugin.installedAt,
        updatedAt = plugin.updatedAt,
        lastError = plugin.lastError,
        grantedPermissionsJson = json.encodeToString(plugin.grantedPermissions)
    )

    fun decode(entity: PluginRegistryEntity): RegisteredPlugin = RegisteredPlugin(
        manifest = json.decodeFromString(entity.manifestJson),
        state = PluginLifecycleState.valueOf(entity.state),
        installedAt = entity.installedAt,
        updatedAt = entity.updatedAt,
        lastError = entity.lastError,
        grantedPermissions = json.decodeFromString(entity.grantedPermissionsJson)
    )
}
