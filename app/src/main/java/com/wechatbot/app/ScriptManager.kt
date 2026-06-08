package com.wechatbot.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ScriptManager {
    private const val TAG = "ScriptManager"
    private const val SCRIPTS_DIR = "scripts"

    private val scriptFiles = listOf(
        "ZynWechatBot_decrypted.py",
        "persona_manager.py",
        "start_bot.sh"
    )

    /**
     * Checks if scripts are already initialized in internal storage.
     */
    fun isInitialized(context: Context): Boolean {
        val dir = getScriptDir(context)
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Returns the File object for the scripts directory.
     */
    fun getScriptDir(context: Context): File {
        return File(context.filesDir, SCRIPTS_DIR)
    }

    /**
     * Copies Python scripts from APK assets to internal storage on first run.
     */
    fun copyScriptsFromAssets(context: Context) {
        val targetDir = getScriptDir(context)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (scriptName in scriptFiles) {
            val targetFile = File(targetDir, scriptName)
            if (targetFile.exists()) {
                Log.d(TAG, "Script $scriptName already exists, skipping copy")
                continue
            }

            try {
                val assetPath = "scripts/$scriptName"
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Set executable permissions on .sh files
                if (scriptName.endsWith(".sh")) {
                    targetFile.setExecutable(true, false)
                }

                Log.d(TAG, "Copied $scriptName to $targetFile")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy script $scriptName", e)
            }
        }
    }

    /**
     * Ensures Python dependencies are installed via Termux's pip.
     * Runs `/data/data/com.termux/files/usr/bin/pip install pycryptodome pilk`.
     */
    fun ensureDependencies(context: Context) {
        Thread {
            try {
                val pipPath = "/data/data/com.termux/files/usr/bin/pip"
                val pipFile = File(pipPath)
                val command = if (pipFile.canExecute()) {
                    "$pipPath install pycryptodome pilk"
                } else {
                    // fallback: try python -m pip
                    val pythonPath = "/data/data/com.termux/files/usr/bin/python"
                    "$pythonPath -m pip install pycryptodome pilk"
                }
                Log.d(TAG, "Running: $command")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    Log.d(TAG, "Dependencies installed successfully")
                } else {
                    Log.e(TAG, "Failed to install dependencies, exit code: $exitCode")
                    process.errorStream.bufferedReader().use { reader ->
                        Log.e(TAG, "Error output: ${reader.readText()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error installing dependencies", e)
            }
        }.start()
    }
}
