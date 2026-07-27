package com.readiness.app

import android.app.Application
import com.readiness.app.work.Scheduler

class ReadinessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.scheduleDaily(this)
    }
}
