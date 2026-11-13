package com.driverguard.eyetrack

import android.app.Application

class DriverGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DriverGuardApp
            private set
    }
}
