package io.github.teamclouday.androidMic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdbReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "AdbReceiver"
        const val ACTION_CONNECT = "io.github.teamclouday.androidMic.CONNECT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        if (intent.action == ACTION_CONNECT) {
            Log.d(TAG, "Triggering auto-connect...")

            val serviceIntent = Intent(context, io.github.teamclouday.androidMic.domain.service.ForegroundService::class.java).apply {
                action = "io.github.teamclouday.androidMic.AUTO_CONNECT"
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
