package com.codecsrayo.presionbp

import android.app.Application
import com.codecsrayo.presionbp.notify.Notifier

class PresionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
