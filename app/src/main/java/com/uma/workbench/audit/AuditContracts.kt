package com.uma.workbench.audit

enum class SourceKind { GITHUB_REPOSITORY, ARCHIVE, SO, IL2CPP_METADATA, SQLITE, MASTER, META_MD5, SESSION, LOG }
enum class Maintenance { ACTIVE, LOW_FREQUENCY, STABLE, PAUSED, ARCHIVED, PROBABLY_ABANDONED, UNKNOWN }
enum class Usability { RUNNABLE, BUILDABLE_PARTIAL, REPAIR_REQUIRED, SOURCE_ONLY, HISTORICAL_ONLY, UNUSABLE, UNKNOWN }
enum class Freshness { CURRENT, PROBABLY_CURRENT, HISTORICAL, UNKNOWN, OBSOLETE, CONFLICTING }
enum class FindingConfidence { CLUE, CANDIDATE, PARTIALLY_CONFIRMED, CONFIRMED, REJECTED }

/**
 * Audit execution policy. It deliberately has no file-count or file-size limit: every selected file
 * remains in scope and large files are consumed by their indexer's resumable batches. The time
 * slice is only a checkpoint boundary for one worker invocation, never a processing limit.
 */
data class AuditBudget(val runtimeSliceMillis: Long = 4L * 60 * 1_000) {
    init { require(runtimeSliceMillis > 0) { "runtimeSliceMillis must be positive" } }
}

/** Pure deadline helper used at durable batch boundaries. */
class AuditRunWindow(
    private val budget: AuditBudget,
    private val startedAtMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun shouldCheckpoint(completedBatches: Long): Boolean =
        completedBatches > 0 && clock() - startedAtMillis >= budget.runtimeSliceMillis
}

data class RepositoryIdentity(val fullName: String, val purpose: String?, val languages: Set<String>, val defaultBranch: String, val maintenance: Maintenance, val usability: Usability, val freshness: Freshness, val forkOf: String?, val duplicateOf: String?)
data class AuditFinding(val sourceId: String, val kind: String, val title: String, val evidencePath: String?, val confidence: FindingConfidence, val version: String?, val summary: String)
data class AuditCheckpoint(val sourceId: String, val stage: String, val cursor: String?, val scannedFiles: Int, val findings: Int)

interface SourceAuditor {
    val supportedKinds: Set<SourceKind>
    suspend fun audit(sourceId: String, checkpoint: AuditCheckpoint?): AuditCheckpoint
}
