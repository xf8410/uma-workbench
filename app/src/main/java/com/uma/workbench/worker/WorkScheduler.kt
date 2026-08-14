package com.uma.workbench.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class WorkScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun scheduleAudit(workItemId: String) {
        val request = OneTimeWorkRequestBuilder<AuditWorker>()
            .setInputData(workDataOf("workItemId" to workItemId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("audit")
            .build()
        manager.enqueueUniqueWork("audit-$workItemId", ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("sync")
            .build()
        manager.enqueueUniqueWork("offline-sync", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAudit(workItemId: String) = manager.cancelUniqueWork("audit-$workItemId")
}
