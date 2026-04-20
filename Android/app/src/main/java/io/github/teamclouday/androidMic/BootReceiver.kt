package io.github.teamclouday.androidMic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "Received: ${intent.action}")
        
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootReceiver", "Boot completed, starting service")
            
            val serviceIntent = Intent(context, io.github.teamclouday.androidMic.domain.service.ForegroundService::class.java).apply {
                action = io.github.teamclouday.androidMic.domain.service.BIND_SERVICE_ACTION
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}