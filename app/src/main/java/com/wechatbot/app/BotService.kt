package com.wechatbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BotService : Service() {

    companion object {
        private const val TAG = "BotService"
        private const val NOTIFICATION_CHANNEL_ID = "bot_service"
        private const val NOTIFICATION_ID = 1
        const val BROADCAST_ACTION = "com.wechatbot.app.BOT_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PORT = "port"
        const val EXTRA_MESSAGE = "message"

        // Status constants
        const val STATUS_STARTING = "starting"
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"

        // Script configuration
        private const val PYTHON_PATH = "/data/data/com.termux/files/usr/bin/python"
        private const val SCRIPT_NAME = "ZynWechatBot_decrypted.py"
        private const val DEFAULT_PORT = 8080

        // Static state
        private val isRunning = AtomicBoolean(false)
        private val detectedPort = AtomicInteger(DEFAULT_PORT)
        private val processRef = AtomicReference<java.lang.Process?>()

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()

        @JvmStatic
        fun getPort(): Int = detectedPort.get()
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private var processJob: Job? = null
    private var restartCount = 0
    private val maxRestarts = 3

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startBotProcess()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): Nothing? = null

    override fun onDestroy() {
        stopBotProcess()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        stopForeground(true)
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = createNotification(STATUS_STARTING, "Starting bot service...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for WechatBot"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WechatBot Service")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String, message: String) {
        val notification = createNotification(status, message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(status: String, port: Int = detectedPort.get(), message: String = "") {
        val intent = Intent(BROADCAST_ACTION).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun startBotProcess() {
        if (isRunning.get()) {
            broadcastStatus(STATUS_ERROR, message = "Service already running")
            return
        }

        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
        }

        isRunning.set(true)
        restartCount = 0
        broadcastStatus(STATUS_STARTING)

        processJob = CoroutineScope(Dispatchers.IO).launch {
            runBotLoop()
        }
    }

    private suspend fun runBotLoop() = withContext(Dispatchers.IO) {
        while (isRunning.get() && restartCount <= maxRestarts) {
            try {
                val scriptFile = File(filesDir, "scripts/$SCRIPT_NAME")
                if (!scriptFile.exists()) {
                    broadcastStatus(STATUS_ERROR, message = "Script not found: ${scriptFile.absolutePath}")
                    isRunning.set(false)
                    break
                }

                val processBuilder = ProcessBuilder(PYTHON_PATH, scriptFile.absolutePath)
                processBuilder.directory(scriptFile.parentFile)
                processBuilder.redirectErrorStream(true)

                val process: java.lang.Process = processBuilder.start()
                processRef.set(process)

                broadcastStatus(STATUS_RUNNING, message = "Bot process started (Restart #$restartCount)")

                // Read combined stdout+stderr
                val reader = BufferedReader(InputStreamReader(process.getInputStream()))
                reader.useLines { lines ->
                    lines.forEach { line ->
                        processOutputLine(line)
                        android.util.Log.d(TAG, "Python: $line")
                    }
                }

                val exitCode = process.waitFor()
                processRef.set(null)

                if (!isRunning.get()) {
                    break
                }

                restartCount++
                if (restartCount > maxRestarts) {
                    broadcastStatus(STATUS_ERROR, message = "Process crashed too many times")
                    isRunning.set(false)
                    break
                }

                Thread.sleep(2000)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in bot loop", e)
                broadcastStatus(STATUS_ERROR, message = e.message ?: "Unknown error")
                isRunning.set(false)
                break
            }
        }

        if (isRunning.get()) {
            stopSelf()
        }
    }

    private fun processOutputLine(line: String) {
        // Debug logging
        android.util.Log.d(TAG, "Python: $line")

        // Check for port detection
        // Expected format: "Running on http://localhost:8080"
        val portRegex = Regex("""http://localhost:(\d+)""")
        val match = portRegex.find(line)
        if (match != null) {
            val port = match.groupValues[1].toInt()
            detectedPort.set(port)
            broadcastStatus(STATUS_RUNNING, port = port, message = "Bot running on port $port")
        }
    }

    private fun stopBotProcess() {
        isRunning.set(false)
        processJob?.cancel()
        processJob = null

        val process = processRef.get()
        if (process != null) {
            try {
                process.destroy()
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error stopping process", e)
            }
            processRef.set(null)
        }

        broadcastStatus(STATUS_STOPPED, message = "Service stopped")
    }
}
