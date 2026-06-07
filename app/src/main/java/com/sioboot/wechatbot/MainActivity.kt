package com.sioboot.wechatbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusText: TextView
    private var logPollingThread: Thread? = null
    @Volatile private var polling = false
    private var lastLogOffset = 0L
    private var browserOpened = false

    companion object {
        const val ACTION_LOG_UPDATE = "com.sioboot.wechatbot.LOG_UPDATE"
        const val ACTION_SERVER_READY = "com.sioboot.wechatbot.SERVER_READY"
        const val ACTION_PYTHON_EXIT = "com.sioboot.wechatbot.PYTHON_EXIT"
        const val EXTRA_LOG_LINE = "log_line"
        const val EXTRA_URL = "url"
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_LOG_UPDATE -> {
                    val line = intent.getStringExtra(EXTRA_LOG_LINE) ?: return
                    appendLog(line)
                }
                ACTION_SERVER_READY -> {
                    val url = intent.getStringExtra(EXTRA_URL) ?: "http://localhost:8888"
                    openBrowser(url)
                }
                ACTION_PYTHON_EXIT -> {
                    val code = intent.getIntExtra("exit_code", -1)
                    appendLog("\n[系统] Python 进程已退出 (code=$code)")
                    statusText.text = "已停止"
                    statusText.setTextColor(Color.parseColor("#F44336"))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createLayout())
        startPythonService()
        setupLogPolling()
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 48, 24, 24)
        }

        // 标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val title = TextView(this).apply {
            text = "WeChat Bot"
            textSize = 22f
            setTextColor(Color.parseColor("#4CAF50"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        statusText = TextView(this).apply {
            text = "启动中..."
            textSize = 14f
            setTextColor(Color.parseColor("#FFC107"))
            setPadding(24, 0, 0, 0)
        }

        header.addView(title)
        header.addView(statusText)

        // 日志区域
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(16, 16, 16, 16)
            isVerticalScrollBarEnabled = true
        }

        logTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }

        scrollView.addView(logTextView)

        // 底部按钮栏
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val btnOpenBrowser = TextView(this).apply {
            text = "  打开浏览器  "
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1976D2"))
            setPadding(32, 16, 32, 16)
            setOnClickListener { openBrowser("http://localhost:8888") }
        }

        val btnRestart = TextView(this).apply {
            text = "  重启服务  "
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F57C00"))
            setPadding(32, 16, 32, 16)
            setMargins(16, 0, 0, 0)
            setOnClickListener {
                appendLog("\n[系统] 正在重启...")
                browserOpened = false
                statusText.text = "重启中..."
                statusText.setTextColor(Color.parseColor("#FFC107"))
                stopService(Intent(this@MainActivity, PythonService::class.java))
                startPythonService()
            }
        }

        bottomBar.addView(btnOpenBrowser)
        bottomBar.addView(btnRestart)

        root.addView(header)
        root.addView(scrollView)
        root.addView(bottomBar)

        return root
    }

    private fun View.setMargins(left: Int, top: Int, right: Int, bottom: Int) {
        val params = layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        params.setMargins(left, top, right, bottom)
        layoutParams = params
    }

    private fun startPythonService() {
        val intent = Intent(this, PythonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "运行中..."
        statusText.setTextColor(Color.parseColor("#4CAF50"))
        appendLog("[系统] 服务已启动，等待 Python 初始化...\n")
    }

    private fun setupLogPolling() {
        polling = true
        logPollingThread = Thread {
            val logFile = File(filesDir, "python_stdout.log")
            while (polling) {
                try {
                    if (logFile.exists()) {
                        RandomAccessFile(logFile, "r").use { raf ->
                            if (raf.length() > lastLogOffset) {
                                raf.seek(lastLogOffset)
                                var line = raf.readLine()
                                while (line != null) {
                                    val decoded = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                                    runOnUiThread { appendLog(decoded) }
                                    lastLogOffset = raf.filePointer
                                    line = raf.readLine()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun appendLog(line: String) {
        logTextView.append(line + "\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

        // 自动检测 HTTP Server 就绪
        if (!browserOpened && line.contains("http://0.0.0.0:8888") ||
            line.contains("HTTP server") && line.contains("8888") ||
            line.contains("Serving HTTP") && line.contains("8888") ||
            line.contains("服务器已启动") ||
            line.contains("Web 管理界面") ||
            line.contains("http://localhost:8888")
        ) {
            browserOpened = true
            openBrowser("http://localhost:8888")
        }
    }

    private fun openBrowser(url: String) {
        if (browserOpened && intent.action == ACTION_SERVER_READY) return
        browserOpened = true
        appendLog("[系统] 打开浏览器: $url")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("[系统] 无法打开浏览器: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_LOG_UPDATE)
            addAction(ACTION_SERVER_READY)
            addAction(ACTION_PYTHON_EXIT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
