package com.zerointerrupt.clicker

import android.app.Application
import com.zerointerrupt.clicker.automation.LocalRpcServer
import com.zerointerrupt.clicker.shizuku.ShizukuController

class ZeroInterruptApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuController.initialize(this)
        LocalRpcServer.syncWithSettings(this)
    }
}
