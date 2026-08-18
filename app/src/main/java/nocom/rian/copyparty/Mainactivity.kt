package nocom.rian.copyparty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etHttpPort = findViewById<EditText>(R.id.etHttpPort)
        val cbEnableFtp = findViewById<CheckBox>(R.id.cbEnableFtp)
        val etFtpPort = findViewById<EditText>(R.id.etFtpPort)
        val etSharedPath = findViewById<EditText>(R.id.etSharedPath)
        val etUploadHook = findViewById<EditText>(R.id.etUploadHook)
        val cbUseCustomConfig = findViewById<CheckBox>(R.id.cbUseCustomConfig)
        val etCustomArgs = findViewById<EditText>(R.id.etCustomArgs)
        val btnResetArgs = findViewById<Button>(R.id.btnResetArgs)
        val btnEditConfig = findViewById<Button>(R.id.btnEditConfig)
        val btnStart = findViewById<Button>(R.id.btnStart)

        // Load custom config preference
        val sharedPref = getSharedPreferences("copyparty_prefs", Context.MODE_PRIVATE)
        cbUseCustomConfig.isChecked = sharedPref.getBoolean("use_custom_config", false)

        val defaultArgs = "--sig-thr"
        val savedArgs = sharedPref.getString("custom_arguments", defaultArgs)
        
        // Reset old defaults containing gather-threads, no-vhash, --th-no-webp, or -j 1 to defaultArgs
        val finalArgs = if (savedArgs != null && (
            savedArgs.contains("gather-threads") || 
            savedArgs.contains("no-vhash") || 
            savedArgs.contains("--th-no-webp") || 
            savedArgs.contains("-j 1")
        )) defaultArgs else (savedArgs ?: defaultArgs)
        etCustomArgs.setText(finalArgs)

        btnResetArgs.setOnClickListener {
            etCustomArgs.setText(defaultArgs)
            sharedPref.edit().putString("custom_arguments", defaultArgs).apply()
        }

        // Enable/disable form inputs based on check state
        val initEnabled = !cbUseCustomConfig.isChecked
        etHttpPort.isEnabled = initEnabled
        cbEnableFtp.isEnabled = initEnabled
        etFtpPort.isEnabled = initEnabled
        etSharedPath.isEnabled = initEnabled
        etUploadHook.isEnabled = initEnabled

        cbUseCustomConfig.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("use_custom_config", isChecked).apply()
            val enabled = !isChecked
            etHttpPort.isEnabled = enabled
            cbEnableFtp.isEnabled = enabled
            etFtpPort.isEnabled = enabled
            etSharedPath.isEnabled = enabled
            etUploadHook.isEnabled = enabled
        }

        btnEditConfig.setOnClickListener {
            val intent = Intent(this, ConfigEditorActivity::class.java)
            startActivityForResult(intent, 102)
        }
        val btnViewLogs = findViewById<Button>(R.id.btnViewLogs)
        btnViewLogs.setOnClickListener {
            val intent = Intent(this, LogViewerActivity::class.java)
            startActivity(intent)
        }
        val btnManagePackages = findViewById<Button>(R.id.btnManagePackages)
        btnManagePackages.setOnClickListener {
            val intent = Intent(this, PackageManagerActivity::class.java)
            startActivity(intent)
        }
        val btnCheckUpdates = findViewById<Button>(R.id.btnCheckUpdates)
        btnCheckUpdates.setOnClickListener {
            checkForCopypartyUpdates()
        }
        // Request permissions on app launch
        checkStoragePermission()
        checkNotificationPermission()

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            btnStart.postDelayed({ btnStart.isEnabled = true }, 1000)
            val serviceIntent = Intent(this, ServerService::class.java)
            
            if (ServerService.isRunning) {
                // Stop the service properly
                stopService(serviceIntent)
                btnStart.text = "Start Copyparty Server"
                Toast.makeText(this, "Stopping Copyparty...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!hasStoragePermission()) {
                Toast.makeText(this, "Please grant All Files Access first!", Toast.LENGTH_SHORT).show()
                checkStoragePermission()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    checkNotificationPermission()
                }
            }

            val httpPort = etHttpPort.text.toString()
            val configFile = File(filesDir, "copyparty.conf")
            val useCustom = cbUseCustomConfig.isChecked

            if (!useCustom) {
                val enableFtp = cbEnableFtp.isChecked
                val ftpPort = etFtpPort.text.toString()
                val sharedPath = etSharedPath.text.toString()
                val uploadHook = etUploadHook.text.toString()

                ConfigWriter.generateConfig(
                    httpPort = httpPort,
                    enableFtp = enableFtp,
                    ftpPort = ftpPort,
                    sharedPath = sharedPath,
                    uploadHook = uploadHook,
                    outputFile = configFile
                )
            } else {
                if (!configFile.exists()) {
                    Toast.makeText(this, "Custom config file not found! Please edit and save it first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val customArgs = etCustomArgs.text.toString()
            sharedPref.edit().putString("custom_arguments", customArgs).apply()

            // Pass execution extras to ServerService
            serviceIntent.putExtra("USE_CUSTOM_CONFIG", useCustom)
            serviceIntent.putExtra("CONFIG_PATH", configFile.absolutePath)
            serviceIntent.putExtra("CUSTOM_ARGS", customArgs)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            btnStart.text = "Stop Copyparty Server"
            val displayPort = if (useCustom) "configured port" else httpPort
            Toast.makeText(this, "Copyparty Started on $displayPort!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Managed by standard AndroidManifest storage permissions on API < 30
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102 && resultCode == RESULT_OK) {
            val cbUseCustomConfig = findViewById<CheckBox>(R.id.cbUseCustomConfig)
            cbUseCustomConfig.isChecked = true
        }
    }

    override fun onResume() {
        super.onResume()
        val btnStart = findViewById<Button>(R.id.btnStart)
        if (ServerService.isRunning) {
            btnStart.text = "Stop Copyparty Server"
        } else {
            btnStart.text = "Start Copyparty Server"
        }
    }

    private fun checkForCopypartyUpdates() {
        val btnCheckUpdates = findViewById<Button>(R.id.btnCheckUpdates)
        btnCheckUpdates.isEnabled = false
        Toast.makeText(this, "Checking PyPI for Copyparty updates...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                val py = Python.getInstance()
                val globals = py.getModule("builtins").callAttr("dict")
                
                py.getModule("builtins").callAttr("exec", """
                    import sys
                    import os
                    import importlib
                    
                    target_dir = os.path.join(sys.prefix, "files", "site-packages")
                    if os.path.exists(target_dir) and target_dir not in sys.path:
                        sys.path.insert(0, target_dir)
                        
                    importlib.invalidate_caches()
                    if hasattr(sys, 'path_importer_cache'):
                        sys.path_importer_cache.clear()
                        
                    to_remove = [mod for mod in sys.modules if mod == 'copyparty' or mod.startswith('copyparty.')]
                    for mod in to_remove:
                        sys.modules.pop(mod, None)
                        
                    import importlib.metadata
                    try:
                        version = importlib.metadata.version('copyparty')
                    except Exception:
                        version = '1.20.20'
                """.trimIndent(), globals)
                
                val currentVersion = globals.callAttr("get", "version").toString()

                val conn = URL("https://pypi.org/pypi/copyparty/json").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val latestVersion = JSONObject(text).getJSONObject("info").getString("version")
                    
                    runOnUiThread {
                        btnCheckUpdates.isEnabled = true
                        if (latestVersion != currentVersion) {
                            showUpdateDialog(currentVersion, latestVersion)
                        } else {
                            Toast.makeText(this@MainActivity, "Copyparty is up to date ($currentVersion)", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        btnCheckUpdates.isEnabled = true
                        Toast.makeText(this@MainActivity, "Could not fetch updates from PyPI (HTTP ${conn.responseCode})", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnCheckUpdates.isEnabled = true
                    Toast.makeText(this@MainActivity, "Failed to check for updates: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showUpdateDialog(currentVersion: String, latestVersion: String) {
        AlertDialog.Builder(this)
            .setTitle("Copyparty Update Available")
            .setMessage("A newer version of Copyparty ($latestVersion) is available on PyPI.\n\nInstalled version: $currentVersion\n\nWould you like to download and install the update? The server service will be stopped during installation.")
            .setPositiveButton("Update") { _, _ ->
                performUpdate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUpdate() {
        val btnStart = findViewById<Button>(R.id.btnStart)
        val serviceIntent = Intent(this, ServerService::class.java)
        if (ServerService.isRunning) {
            stopService(serviceIntent)
            btnStart.text = "Start Copyparty Server"
            Toast.makeText(this, "Stopping server for update...", Toast.LENGTH_SHORT).show()
        }

        Toast.makeText(this, "Updating Copyparty... Check Log Viewer for details.", Toast.LENGTH_LONG).show()
        
        val targetDir = File(filesDir, "site-packages").absolutePath
        PipRunner.run(this, arrayOf("install", "--upgrade", "--target", targetDir, "copyparty")) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this@MainActivity, "Copyparty updated successfully! Start the server to apply changes.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Copyparty update failed! Please check logs.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

