package com.nsaanchor.gatekeeper

import android.app.Application
import android.os.Build
import com.nsaanchor.gatekeeper.data.GatekeeperRepository
import org.lsposed.hiddenapibypass.HiddenApiBypass

class GatekeeperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
        GatekeeperRepository.init(this)
    }

    companion object {
        lateinit var instance: GatekeeperApp
            private set
    }
}
