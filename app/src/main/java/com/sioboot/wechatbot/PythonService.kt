package com.sioboot.wechatbot

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import java.io.File

class PythonService : Service() {

    companion object {
        const val TAG = "PythonService"
        const val CHANNEL_ID = "wechat_bot_service"
        const val NOTIFICATION_ID = 1
    }

    private var pythonThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("WeChat Bot 运行中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPython()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPython() {
        if (pythonThread?.isAlive == true) return

        pythonThread = Thread {
            try {
                // 清空旧日志
                val logFile = File(filesDir, "python_stdout.log")
                logFile.writeText("[系统] WeChat Bot Android 版启动中...\n")

                sendLog("[系统] 初始化 Python 运行环境...")

                val py = Python.getInstance()

                // 复制 Python 文件到可写目录
                val pyDir = File(filesDir, "python_scripts")
                pyDir.mkdirs()
                copyAssetFiles("python", pyDir)

                // 设置 bot 数据目录（配置/消息/缓存）
                val botDataDir = File(filesDir, "bot_data")
                botDataDir.mkdirs()

                // 注入路径
                val sys = py.getModule("sys")
                val pathList = sys?.get("path")
                pathList?.callAttr("insert", 0, pyDir.absolutePath)

                // 设置工作目录
                val os = py.getModule("os")
                os?.callAttr("chdir", botDataDir.absolutePath)

                // 设置 HOME 环境变量（脚本用来定位日志文件）
                os?.callAttr("environ",)?.callAttr("__setitem__", "HOME", filesDir.absolutePath)

                sendLog("[系统] Python 路径已配置，启动脚本...")

                // 优先用 Android 启动器脚本
                val launcher = File(pyDir, "main_android.py")
                val mainScript = File(pyDir, "ZynWechatBot_decrypted.py")

                if (launcher.exists() && mainScript.exists()) {
                    sendLog("[系统] 使用 Android 启动器")
                    val code = launcher.readText(Charsets.UTF_8)
                    py.getModule("builtins")?.callAttr("exec", code)
                } else if (mainScript.exists()) {
                    sendLog("[系统] 直接执行主脚本")
                    val code = mainScript.readText(Charsets.UTF_8)
                    py.getModule("builtins")?.callAttr("exec", code)
                } else {
                    val msg = "[错误] 找不到 Python 脚本 (检查: ${pyDir.listFiles()?.map { it.name }})"
                    Log.e(TAG, msg)
                    sendLog(msg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Python 执行错误", e)
                sendLog("[错误] ${e.javaClass.simpleName}: ${e.message}")
                sendExit(-1)
            }
        }.apply {
            isDaemon = true
            name = "PythonMainThread"
            start()
        }
    }

    private fun copyAssetFiles(assetDir: String, targetDir: File) {
        try {
            val assetList = assets.list(assetDir) ?: return
            for (filename in assetList) {
                val assetPath = if (assetDir.isEmpty()) filename else "$assetDir/$filename"
                val targetFile = File(targetDir, filename)

                val subAssets = assets.list(assetPath)
                if (subAssets != null && subAssets.isNotEmpty()) {
                    targetFile.mkdirs()
                    copyAssetFiles(assetPath, targetFile)
                } else {
                    // 只在目标文件不存在或大小不同时复制
                    val assetSize = assets.openFd(assetPath).use { it.length }
                    if (!targetFile.exists() || targetFile.length() != assetSize) {
                        assets.open(assetPath).use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "已复制: $assetPath → ${targetFile.absolutePath}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制资产失败: $assetDir", e)
        }
    }

    private fun sendLog(line: String) {
        val intent = Intent(MainActivity.ACTION_LOG_UPDATE).apply {
            putExtra(MainActivity.EXTRA_LOG_LINE, line)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        try {
            val logFile = File(filesDir, "python_stdout.log")
            logFile.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    private fun sendExit(code: Int) {
        val intent = Intent(MainActivity.ACTION_PYTHON_EXIT).apply {
            putExtra("exit_code", code)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WeChat Bot 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 WeChat Bot 后台运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WeChat Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        pythonThread?.interrupt()
        super.onDestroy()
    }
}
