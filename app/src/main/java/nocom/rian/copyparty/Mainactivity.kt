package nocom.rian.copyparty

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
        val btnStart = findViewById<Button>(R.id.btnStart)

        // Request permissions on app launch
        checkStoragePermission()
        checkNotificationPermission()


        btnStart.setOnClickListener {
            if (!hasStoragePermission()) {
                Toast.makeText(this, "Please grant All Files Access first!", Toast.LENGTH_SHORT).show()
                checkStoragePermission()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Please grant Notification Permission first!", Toast.LENGTH_SHORT).show()
                    checkNotificationPermission()
                    return@setOnClickListener
                }
            }

            val httpPort = etHttpPort.text.toString()
            val enableFtp = cbEnableFtp.isChecked
            val ftpPort = etFtpPort.text.toString()
            val sharedPath = etSharedPath.text.toString()
            val uploadHook = etUploadHook.text.toString()

            // Write copyparty.conf to internal storage using ConfigWriter
            val configFile = File(filesDir, "copyparty.conf")
            ConfigWriter.generateConfig(
                httpPort = httpPort,
                enableFtp = enableFtp,
                ftpPort = ftpPort,
                sharedPath = sharedPath,
                uploadHook = uploadHook,
                outputFile = configFile
            )
            // Launch Server Service
            val serviceIntent = Intent(this, ServerService::class.java).apply {
                putExtra("CONFIG_PATH", configFile.absolutePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "Copyparty Started on Port $httpPort!", Toast.LENGTH_SHORT).show()
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
}
