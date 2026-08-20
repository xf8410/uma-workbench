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

    fun scheduleDiary(agentId: String? = null, intervalHours: Long = 24) {
        val inputData = workDataOf("agentId" to agentId)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<DiaryWorker>(intervalHours, TimeUnit.HOURS)
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .addTag("diary")
            .build()
        val uniqueWorkName = if (agentId != null) "diary-$agentId" else "diary-all"
        manager.enqueueUniquePeriodicWork(uniqueWorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelDiary(agentId: String? = null) {
        val uniqueWorkName = if (agentId != null) "diary-$agentId" else "diary-all"
        manager.cancelUniqueWork(uniqueWorkName)
    }

    fun cancelAudit(workItemId: String) = manager.cancelUniqueWork("audit-$workItemId")
}
