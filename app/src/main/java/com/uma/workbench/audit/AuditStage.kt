package com.uma.workbench.audit

enum class AuditStage { DISCOVERY, FINGERPRINT, FILE_INDEX, TEXT_INDEX, BINARY_INDEX, DATABASE_SCHEMA, VERSION_LINK, EVIDENCE, SUMMARY }

data class StageTransition(val from: AuditStage, val to: AuditStage, val checkpoint: String? = null)

object AuditStageMachine {
    private val order = AuditStage.entries
    fun next(stage: AuditStage): AuditStage? = order.getOrNull(order.indexOf(stage) + 1)
    fun canResume(stage: AuditStage, checkpoint: String?): Boolean = stage in order && (checkpoint == null || checkpoint.length <= 4_096)
    fun transition(stage: AuditStage, checkpoint: String?): StageTransition? = next(stage)?.let { StageTransition(stage, it, checkpoint) }
}
