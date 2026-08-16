package com.uma.workbench.agent

/** One readable workspace document. URI is retained exactly so results can be reopened or attached. */
data class WorkspaceSearchDocument(
    val workspaceId: String,
    val uri: String,
    val title: String
)

data class WorkspaceSearchMatch(
    val workspaceId: String,
    val uri: String,
    val title: String,
    val lineNumber: Int,
    val columnNumber: Int,
    val completeLine: String,
    val matchStartInLine: Int,
    val matchEndExclusiveInLine: Int
)

data class WorkspaceSearchFailure(
    val uri: String,
    val completeError: String
)

data class WorkspaceSearchPage(
    val query: String,
    val caseSensitive: Boolean,
    val offset: Int,
    val matches: List<WorkspaceSearchMatch>,
    val totalMatches: Int,
    val nextOffset: Int?,
    val scannedDocuments: Int,
    val availableDocuments: Int,
    val documentsExcludedByLimit: Int,
    val partiallyScannedUris: List<String>,
    val failures: List<WorkspaceSearchFailure>
) {
    val isCompleteDocumentScan: Boolean
        get() = scannedDocuments == availableDocuments && failures.isEmpty()
}

fun interface WorkspaceDocumentTextReader {
    suspend fun readCompleteText(document: WorkspaceSearchDocument): String
}

class WorkspaceReadonlySearch(
    private val reader: WorkspaceDocumentTextReader
) {
    suspend fun search(
        documents: List<WorkspaceSearchDocument>,
        query: String,
        offset: Int = 0,
        caseSensitive: Boolean = false
    ): WorkspaceSearchPage {
        require(query.isNotEmpty()) { "Search query must not be empty" }
        require(offset >= 0) { "Search offset must not be negative" }

        val deduplicated = documents.distinctBy { Triple(it.workspaceId, it.uri, it.title) }
        val allMatches = mutableListOf<WorkspaceSearchMatch>()
        val failures = mutableListOf<WorkspaceSearchFailure>()

        deduplicated.forEach { document ->
            runCatching { reader.readCompleteText(document) }
                .onSuccess { completeText -> collectMatches(document, completeText, query, caseSensitive, allMatches) }
                .onFailure { error -> failures += WorkspaceSearchFailure(document.uri, error.stackTraceToString()) }
        }

        return WorkspaceSearchPage(
            query = query,
            caseSensitive = caseSensitive,
            offset = 0,
            matches = allMatches,
            totalMatches = allMatches.size,
            nextOffset = null,
            scannedDocuments = deduplicated.size,
            availableDocuments = deduplicated.size,
            documentsExcludedByLimit = 0,
            partiallyScannedUris = emptyList(),
            failures = failures
        )
    }

    private fun collectMatches(
        document: WorkspaceSearchDocument,
        text: String,
        query: String,
        caseSensitive: Boolean,
        destination: MutableList<WorkspaceSearchMatch>
    ) {
        var lineNumber = 1
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val rawEnd = if (newline >= 0) newline else text.length
            val contentEnd = if (rawEnd > lineStart && text[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            val completeLine = text.substring(lineStart, contentEnd)
            var from = 0
            while (from <= completeLine.length - query.length) {
                val found = completeLine.indexOf(query, startIndex = from, ignoreCase = !caseSensitive)
                if (found < 0) break
                destination += WorkspaceSearchMatch(
                    workspaceId = document.workspaceId,
                    uri = document.uri,
                    title = document.title,
                    lineNumber = lineNumber,
                    columnNumber = found + 1,
                    completeLine = completeLine,
                    matchStartInLine = found,
                    matchEndExclusiveInLine = found + query.length
                )
                from = found + query.length.coerceAtLeast(1)
            }
            if (newline < 0) break
            lineStart = newline + 1
            lineNumber++
        }
    }
}
