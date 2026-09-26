package com.fourj.iptv

import android.app.Application
import com.fourj.iptv.di.AppContainer

class FourJApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
