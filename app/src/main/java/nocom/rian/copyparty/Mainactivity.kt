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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
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
        val btnEditConfig = findViewById<Button>(R.id.btnEditConfig)
        val btnStart = findViewById<Button>(R.id.btnStart)

        // Load custom config preference
        val sharedPref = getSharedPreferences("copyparty_prefs", Context.MODE_PRIVATE)
        cbUseCustomConfig.isChecked = sharedPref.getBoolean("use_custom_config", false)

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

        // Request permissions on app launch
        checkStoragePermission()
        checkNotificationPermission()

        btnStart.setOnClickListener {
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

            // Launch Server Service
            serviceIntent.putExtra("CONFIG_PATH", configFile.absolutePath)
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
        // ponytail: read the @Volatile flag directly; getRunningServices is deprecated and races with stopSelf
        if (ServerService.isRunning) {
            btnStart.text = "Stop Copyparty Server"
        } else {
            btnStart.text = "Start Copyparty Server"
        }
    }
}
