package com.jobpilot

import android.app.Application
import com.jobpilot.core.JobRunner
import com.jobpilot.data.Store
import kotlinx.coroutines.runBlocking
import com.jobpilot.work.SearchWorker

class JobPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        // Upgrade stored data in place; nothing the user entered is dropped.
        runBlocking { Store.update { JobRunner.migrate(it) } }
        SearchWorker.schedule(this)
    }
}
