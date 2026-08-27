package com.uma.workbench.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * UI 驱动的审批门：[requestApproval] 挂起等待用户在 UI 上响应，
 * 通过 [respond] 提交决定后唤醒。
 *
 * - 自动批准 READ_ONLY（模式已过滤，到这层默认放行）；
 * - LOCAL_WRITE / REMOTE_WRITE / DESTRUCTIVE 全部走 UI 等待。
 *
 * 同时维护一个 [pending] 状态流供 Compose 观察并渲染对话框。
 */
class UiToolApprovalGate(
    private val autoApproveReadOnly: Boolean = true,
    /** 每次审批决定（含自动批准）的审计回调；抛错不影响审批结果。 */
    private val onDecision: ((ToolApprovalRequest, ToolApprovalDecision) -> Unit)? = null
) : ToolApprovalGate {

    /** 一次请求的句柄：含请求体 + 唤醒用 deferred。 */
    data class Pending(
        val requestId: String,
        val request: ToolApprovalRequest,
        private val deferred: CompletableDeferred<ToolApprovalDecision>
    ) {
        internal fun complete(decision: ToolApprovalDecision) {
            if (decision.callId == request.callId) {
                deferred.complete(decision)
            }
        }
    }

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    private val _events = MutableSharedFlow<ToolApprovalRequest>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
        if (autoApproveReadOnly && request.riskLevel == ToolRiskLevel.READ_ONLY) {
            val auto = ToolApprovalDecision(
                callId = request.callId,
                approved = true,
                reason = "只读工具自动批准"
            )
            notifyDecision(request, auto)
            return auto
        }
        val deferred = CompletableDeferred<ToolApprovalDecision>()
        val handle = Pending(
            requestId = UUID.randomUUID().toString(),
            request = request,
            deferred = deferred
        )
        _pending.value = _pending.value + handle
        _events.emit(request)
        return try {
            val decision = deferred.await()
            notifyDecision(request, decision)
            decision
        } finally {
            _pending.value = _pending.value.filterNot { it.requestId == handle.requestId }
        }
    }

    /** UI 提交审批结果。 */
    fun respond(callId: String, approved: Boolean, reason: String? = null) {
        val decision = ToolApprovalDecision(callId, approved, reason)
        // 决定的审计记录由 requestApproval 的 await 分支统一落，避免双重记录
        _pending.value.firstOrNull { it.request.callId == callId }?.complete(decision)
    }

    /** 当前是否有等待中的请求。 */
    fun hasPending(): Boolean = _pending.value.isNotEmpty()

    private fun notifyDecision(request: ToolApprovalRequest, decision: ToolApprovalDecision) {
        val sink = onDecision ?: return
        runCatching { sink(request, decision) }
    }
}
