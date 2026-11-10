package com.nsaanchor.gatekeeper

import android.app.Application
import com.nsaanchor.gatekeeper.data.GatekeeperRepository

class GatekeeperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        GatekeeperRepository.init(this)
    }

    companion object {
        lateinit var instance: GatekeeperApp
            private set
    }
}
