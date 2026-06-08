package com.wechatbot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(TAG, "Boot completed, starting BotService...")

            // Acquire partial wake lock to ensure service starts
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::WakeLock"
            )
            wakeLock.acquire(10000) // 10 seconds timeout

            try {
                // Check if scripts are initialized before starting service
                if (ScriptManager.isInitialized(context)) {
                    // Start BotService via startForegroundService
                    val serviceIntent = Intent(context, BotService::class.java)
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "BotService started successfully")
                } else {
                    Log.w(TAG, "Scripts not initialized, skipping service start")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service", e)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }
}
