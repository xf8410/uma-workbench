package com.uma.workbench.plugin

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plugin_registry")
data class PluginRegistryEntity(
    @PrimaryKey val pluginId: String,
    val manifestJson: String,
    val state: String,
    val installedAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    val grantedPermissionsJson: String = "[]"
)
