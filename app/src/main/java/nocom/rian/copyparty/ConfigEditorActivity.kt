package nocom.rian.copyparty

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ConfigEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        val etConfigContent = findViewById<EditText>(R.id.etConfigContent)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnImportFile = findViewById<Button>(R.id.btnImportFile)

        val configFile = File(filesDir, "copyparty.conf")

        // Load existing configuration if it exists
        if (configFile.exists()) {
            try {
                etConfigContent.setText(configFile.readText())
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load config file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Provide a default template
            val defaultTemplate = """
                [global]
                  p: 3923

                [/]
                  /sdcard/Download
                  accs:
                    A: *
            """.trimIndent()
            etConfigContent.setText(defaultTemplate)
        }

        btnImportFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, 103)
        }

        btnSave.setOnClickListener {
            val content = etConfigContent.text.toString()
            try {
                configFile.writeText(content)
                
                // Save custom config preference to SharedPreferences
                val sharedPref = getSharedPreferences("copyparty_prefs", Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putBoolean("use_custom_config", true)
                    apply()
                }

                Toast.makeText(this, "Config saved successfully!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save config: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 103 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                handleFileImport(uri)
            }
        }
    }

    private fun handleFileImport(uri: Uri) {
        val fileName = getFileName(uri)
        val defaultPath = ".config/copyparty/bin/hooks/$fileName"
        val context = this

        val input = EditText(context).apply {
            setText(defaultPath)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            setPadding(40, 20, 40, 20)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle("Import File to App Storage")
            .setMessage("Confirm the target relative path inside App Storage (relative to filesDir):")
            .setView(container)
            .setPositiveButton("Import") { dialog, _ ->
                val targetPath = input.text.toString().trim()
                if (targetPath.isEmpty()) {
                    Toast.makeText(context, "Path cannot be empty!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                try {
                    val targetFile = File(filesDir, targetPath)
                    targetFile.parentFile?.mkdirs()
                    
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    Toast.makeText(context, "Successfully imported to: $targetPath", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "file.py"
    }
}
