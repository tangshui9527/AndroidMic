package io.github.teamclouday.androidMic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.teamclouday.androidMic.domain.service.AUTO_CONNECT_ACTION
import io.github.teamclouday.androidMic.domain.service.ForegroundService

class AdbReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "AdbReceiver"
        const val ACTION_CONNECT = "io.github.teamclouday.androidMic.CONNECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        if (intent.action == ACTION_CONNECT) {
            Log.d(TAG, "Triggering auto-connect...")

            val serviceIntent = Intent(context, ForegroundService::class.java).apply {
                action = AUTO_CONNECT_ACTION
                replaceExtras(intent)
            }

            try {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "Started service for auto-connect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
            }
        }
    }
}
