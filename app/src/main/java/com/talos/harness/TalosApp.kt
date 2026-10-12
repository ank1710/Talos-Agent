package com.talos.harness

import android.app.Application
import com.talos.harness.notifications.TalosNotifications

class TalosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Idempotent, and must exist before the first notification is posted.
        TalosNotifications.ensureChannel(this)
    }
}
