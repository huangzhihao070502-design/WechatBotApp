package com.wechatbot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnOpenBrowser: ImageButton
    private lateinit var tvStatus: TextView

    private var isBotRunning = false
    private var currentPort = 8080
    private var currentUrl = "http://localhost:8080"

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BotService.BROADCAST_ACTION) {
                val status = intent.getStringExtra(BotService.EXTRA_STATUS)
                val port = intent.getIntExtra(BotService.EXTRA_PORT, 8080)
                val message = intent.getStringExtra(BotService.EXTRA_MESSAGE)

                runOnUiThread {
                    updateStatus(status, port, message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        registerReceiver()

        // Initialize scripts on first run
        CoroutineScope(Dispatchers.IO).launch {
            initializeScripts()
            withContext(Dispatchers.Main) {
                startBotService()
            }
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser)
        tvStatus = findViewById(R.id.tvStatus)

        btnRefresh.setOnClickListener {
            if (isBotRunning) {
                webView.reload()
            } else {
                Toast.makeText(this, "Bot is not running", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenBrowser.setOnClickListener {
            openInExternalBrowser()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Handle external links
                val url = request?.url?.toString()
                if (url != null && !url.startsWith("http://localhost") && !url.startsWith("http://127.0.0.1")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        startActivity(this)
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter(BotService.BROADCAST_ACTION)
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterReceiver() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
    }

    private suspend fun initializeScripts() {
        withContext(Dispatchers.Main) {
            tvStatus.text = "Initializing scripts..."
        }

        if (!ScriptManager.isInitialized(this)) {
            ScriptManager.copyScriptsFromAssets(this)
            ScriptManager.ensureDependencies(this)
        }

        withContext(Dispatchers.Main) {
            tvStatus.text = "Scripts initialized"
        }
    }

    private fun startBotService() {
        val intent = Intent(this, BotService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBotService() {
        val intent = Intent(this, BotService::class.java)
        stopService(intent)
    }

    private fun updateStatus(status: String?, port: Int, message: String?) {
        currentPort = port
        currentUrl = "http://localhost:$port"

        when (status) {
            BotService.STATUS_STARTING -> {
                isBotRunning = false
                tvStatus.text = "Starting bot..."
                progressBar.visibility = View.VISIBLE
            }
            BotService.STATUS_RUNNING -> {
                isBotRunning = true
                tvStatus.text = "Running on port $port"
                progressBar.visibility = View.GONE
                loadUrlIfNeeded()
            }
            BotService.STATUS_STOPPED -> {
                isBotRunning = false
                tvStatus.text = "Bot stopped"
                progressBar.visibility = View.GONE
            }
            BotService.STATUS_ERROR -> {
                isBotRunning = false
                tvStatus.text = "Error: $message"
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Bot error: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadUrlIfNeeded() {
        if (webView.url != currentUrl) {
            webView.loadUrl(currentUrl)
        }
    }

    private fun openInExternalBrowser() {
        if (isBotRunning) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Bot is not running", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBotRunning) {
            loadUrlIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver()
        webView.destroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
