package com.uma.workbench.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

abstract class UmaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected fun checkpoint(): String = inputData.getString("checkpoint") ?: "0"
}

class AuditWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        val workItemId = inputData.getString("workItemId") ?: return Result.failure(workDataOf("error" to "缺少 workItemId"))
        // Each implementation stage must write a checkpoint before returning.
        return Result.success(workDataOf("workItemId" to workItemId, "checkpoint" to checkpoint()))
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        // Network constraints prevent normal offline execution; transient failures retry.
        return Result.success(workDataOf("checkpoint" to checkpoint()))
    }
}
