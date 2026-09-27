package com.yusuftahir.ibbwifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("auto_login_enabled", false)
        if (!isEnabled) return

        val serviceIntent = Intent(context, IbbBackgroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (ignored: Exception) {}
    }
}
