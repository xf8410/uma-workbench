package com.uma.workbench.plugin

enum class PluginPermissionChangeType { ADDED, REMOVED }

data class PluginPermissionChange(
    val permission: String,
    val type: PluginPermissionChangeType
)

data class PluginPermissionDiff(
    val changes: List<PluginPermissionChange>
) {
    val added: List<String> get() = changes.filter { it.type == PluginPermissionChangeType.ADDED }.map { it.permission }
    val removed: List<String> get() = changes.filter { it.type == PluginPermissionChangeType.REMOVED }.map { it.permission }
    val requiresReapproval: Boolean get() = added.isNotEmpty()
    val isUnchanged: Boolean get() = changes.isEmpty()

    companion object {
        fun between(old: PluginManifest, next: PluginManifest): PluginPermissionDiff {
            val oldPermissions = old.permissions.toSet()
            val newPermissions = next.permissions.toSet()
            val changes = (newPermissions - oldPermissions).sorted().map {
                PluginPermissionChange(it, PluginPermissionChangeType.ADDED)
            } + (oldPermissions - newPermissions).sorted().map {
                PluginPermissionChange(it, PluginPermissionChangeType.REMOVED)
            }
            return PluginPermissionDiff(changes)
        }
    }
}
