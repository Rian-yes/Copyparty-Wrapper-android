package nocom.rian.copyparty

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val btnClearLogs = findViewById<Button>(R.id.btnClearLogs)

        tvLogs.movementMethod = ScrollingMovementMethod()

        btnClearLogs.setOnClickListener {
            LogManager.clear()
            tvLogs.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        
        // Populate existing logs
        tvLogs.text = LogManager.getLogs().joinToString("")
        scrollToBottom(tvLogs)

        // Listen for new logs
        LogManager.onLogListener = { message ->
            runOnUiThread {
                tvLogs.append(message)
                scrollToBottom(tvLogs)
            }
        }
    }

    override fun onPause() {
        LogManager.onLogListener = null
        super.onPause()
    }

    private fun scrollToBottom(textView: TextView) {
        val scrollAmount = textView.layout?.let { layout ->
            layout.lineCount * textView.lineHeight - textView.height
        } ?: 0
        if (scrollAmount > 0) {
            textView.scrollTo(0, scrollAmount)
        }
    }
}
