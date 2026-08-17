package com.uma.workbench.plugin

internal fun validPluginManifest(
    transport: PluginTransport = PluginTransport.McpHttp("https://plugins.example.com/mcp"),
    authentication: PluginAuthentication = PluginAuthentication.None,
    integrity: PluginIntegrity? = PluginIntegrity("a".repeat(64))
) = PluginManifest(
    id = "com.example.search",
    name = "搜索插件",
    version = "1.2.3",
    publisher = PluginPublisher("com.example", "Example", "https://example.com"),
    transport = transport,
    permissions = listOf("network:plugins.example.com", "workspace:read"),
    authentication = authentication,
    integrity = integrity
)
