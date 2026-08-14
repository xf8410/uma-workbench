package com.uma.workbench

import android.app.Application
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.WorkbenchRepository
import com.uma.workbench.imports.SourceImporter
import com.uma.workbench.network.NetworkMonitor
import com.uma.workbench.worker.WorkScheduler

class WorkbenchApplication : Application() {
    lateinit var repository: WorkbenchRepository; private set
    lateinit var networkMonitor: NetworkMonitor; private set
    lateinit var sourceImporter: SourceImporter; private set
    lateinit var workScheduler: WorkScheduler; private set

    override fun onCreate() {
        super.onCreate()
        repository = WorkbenchRepository(AppDatabase.get(this))
        networkMonitor = NetworkMonitor(this)
        sourceImporter = SourceImporter(contentResolver)
        workScheduler = WorkScheduler(this)
    }
}
