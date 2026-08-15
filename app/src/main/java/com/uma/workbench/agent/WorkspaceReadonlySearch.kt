package com.uma.workbench.agent

/** One readable workspace document. URI is retained exactly so results can be reopened or attached. */
data class WorkspaceSearchDocument(
    val workspaceId: String,
    val uri: String,
    val title: String
)

data class WorkspaceSearchLimits(
    val maxDocuments: Int = 100,
    val maxCharactersPerDocument: Int = 2_000_000,
    val maxMatchesPerPage: Int = 100
) {
    init {
        require(maxDocuments > 0) { "maxDocuments must be positive" }
        require(maxCharactersPerDocument > 0) { "maxCharactersPerDocument must be positive" }
        require(maxMatchesPerPage > 0) { "maxMatchesPerPage must be positive" }
    }
}

data class WorkspaceSearchMatch(
    val workspaceId: String,
    val uri: String,
    val title: String,
    val lineNumber: Int,
    val columnNumber: Int,
    /** Complete matching source line. It is not shortened for presentation. */
    val completeLine: String,
    val matchStartInLine: Int,
    val matchEndExclusiveInLine: Int
)

data class WorkspaceSearchFailure(
    val uri: String,
    /** Complete exception text; callers may show it in a details view. */
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
        get() = documentsExcludedByLimit == 0 && partiallyScannedUris.isEmpty() && failures.isEmpty()
}

fun interface WorkspaceDocumentTextReader {
    /** Returns complete decoded text. The search engine applies its explicit character budget. */
    suspend fun readCompleteText(document: WorkspaceSearchDocument): String
}

/**
 * Deterministic literal search for read-only Agent and UI use.
 *
 * The engine never silently claims completeness: document-count exclusions, per-document partial
 * scans and read failures are returned in every page. Pagination is over the deterministic complete
 * result set within those declared scan limits. Every match retains its complete source line.
 */
class WorkspaceReadonlySearch(
    private val reader: WorkspaceDocumentTextReader,
    private val limits: WorkspaceSearchLimits = WorkspaceSearchLimits()
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
        val selected = deduplicated.take(limits.maxDocuments)
        val allMatches = mutableListOf<WorkspaceSearchMatch>()
        val partialUris = mutableListOf<String>()
        val failures = mutableListOf<WorkspaceSearchFailure>()

        selected.forEach { document ->
            runCatching { reader.readCompleteText(document) }
                .onSuccess { completeText ->
                    val scannedText = if (completeText.length > limits.maxCharactersPerDocument) {
                        partialUris += document.uri
                        completeText.substring(0, limits.maxCharactersPerDocument)
                    } else completeText
                    collectMatches(document, scannedText, query, caseSensitive, allMatches)
                }
                .onFailure { error ->
                    failures += WorkspaceSearchFailure(document.uri, error.stackTraceToString())
                }
        }

        val pageMatches = allMatches.drop(offset).take(limits.maxMatchesPerPage)
        val nextOffset = (offset + pageMatches.size).takeIf { it < allMatches.size }
        return WorkspaceSearchPage(
            query = query,
            caseSensitive = caseSensitive,
            offset = offset,
            matches = pageMatches,
            totalMatches = allMatches.size,
            nextOffset = nextOffset,
            scannedDocuments = selected.size,
            availableDocuments = deduplicated.size,
            documentsExcludedByLimit = (deduplicated.size - selected.size).coerceAtLeast(0),
            partiallyScannedUris = partialUris,
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
