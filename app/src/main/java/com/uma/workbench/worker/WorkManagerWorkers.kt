package com.uma.workbench.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Result

abstract class UmaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected fun checkpoint(): String = inputData.getString("checkpoint") ?: "0"
}

class AuditWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        // Real scanners will consume one bounded source/stage per invocation.
        // Checkpoints are deliberately persisted by the repository layer before each batch.
        return Result.success()
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        // Network changes are retryable, not fatal. Idempotency keys prevent duplicates.
        return Result.success()
    }
}
