package com.airgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the link back after a reboot.
 *
 * Without this the phone is invisible to the desktop until the user
 * remembers to open the app, which they will not: the whole point of a
 * gesture transfer is that you never think about the app at all.
 *
 * Manufacturer autostart restrictions can block this broadcast outright, and
 * on a vivo handset they will unless the user has allowed it. That is what
 * [BatteryPolicy] asks for, and there is no code-only way around it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(AirGrabService.TAG, "restarting after boot")
        runCatching { AirGrabService.start(context) }
            .onFailure { Log.w(AirGrabService.TAG, "could not restart: ${it.message}") }
    }
}
