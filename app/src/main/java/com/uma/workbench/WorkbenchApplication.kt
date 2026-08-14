package com.uma.workbench

import android.app.Application
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.WorkbenchRepository
import com.uma.workbench.network.NetworkMonitor

class WorkbenchApplication : Application() {
    lateinit var repository: WorkbenchRepository
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.get(this)
        repository = WorkbenchRepository(database)
        networkMonitor = NetworkMonitor(this)
    }
}
