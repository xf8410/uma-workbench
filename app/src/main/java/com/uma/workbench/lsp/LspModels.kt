package com.uma.workbench.lsp

/**
 * Built-in LSP server configurations for Rust and Kotlin.
 * These are declarative configurations; the actual LSP server binaries must be
 * available on the device (bundled or user-installed). The Workbench does not
 * download or execute remote binaries automatically.
 */
data class LspServerConfig(
    val id: String,
    val displayName: String,
    val language: String,
    val command: String,
    val args: List<String> = emptyList(),
    val initOptionsJson: String? = null,
    val enabled: Boolean = true
)

object BuiltinLspServers {
    val RUST = LspServerConfig(
        id = "rust-analyzer",
        displayName = "Rust Analyzer",
        language = "rust",
        command = "rust-analyzer",
        args = emptyList()
    )

    val KOTLIN = LspServerConfig(
        id = "kotlin-language-server",
        displayName = "Kotlin Language Server",
        language = "kotlin",
        command = "kotlin-language-server",
        args = emptyList()
    )

    val ALL = listOf(RUST, KOTLIN)

    fun byLanguage(fileExtension: String): LspServerConfig? = when (fileExtension.lowercase()) {
        "rs" -> RUST
        "kt", "kts" -> KOTLIN
        else -> null
    }

    fun byId(id: String): LspServerConfig? = ALL.firstOrNull { it.id == id }
}

data class LspServerCapabilities(
    val serverId: String,
    val textDocumentSync: Int,
    val hoverSupport: Boolean,
    val completionSupport: Boolean,
    val definitionSupport: Boolean,
    val referencesSupport: Boolean,
    val documentSymbolSupport: Boolean,
    val workspaceSymbolSupport: Boolean,
    val foldingRangeSupport: Boolean,
    val selectionRangeSupport: Boolean,
    val semanticTokensSupport: Boolean
)

data class LspDiagnostic(
    val fileUri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: LspDiagnosticSeverity,
    val source: String?,
    val message: String,
    val code: String?
)

enum class LspDiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }

data class LspSymbol(
    val name: String,
    val kind: Int,
    val fileUri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val containerName: String?
)

data class LspHover(
    val fileUri: String,
    val line: Int,
    val column: Int,
    val content: String,
    val contentFormat: String
)
