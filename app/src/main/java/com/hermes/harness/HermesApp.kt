package com.hermes.harness

import android.app.Application
import com.hermes.harness.notifications.HermesNotifications

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Idempotent, and must exist before the first notification is posted.
        HermesNotifications.ensureChannel(this)
    }
}
