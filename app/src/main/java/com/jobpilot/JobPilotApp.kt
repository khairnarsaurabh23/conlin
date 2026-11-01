package com.jobpilot

import android.app.Application
import com.jobpilot.data.Store
import com.jobpilot.work.SearchWorker

class JobPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        SearchWorker.schedule(this)
    }
}
