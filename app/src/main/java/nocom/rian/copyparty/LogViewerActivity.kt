package nocom.rian.copyparty

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val btnClearLogs = findViewById<Button>(R.id.btnClearLogs)

        btnClearLogs.setOnClickListener {
            LogManager.clear()
            tvLogs.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val svLogsContainer = findViewById<ScrollView>(R.id.svLogsContainer)
        
        // Populate existing logs
        tvLogs.text = LogManager.getLogs().joinToString("")
        scrollToBottom(svLogsContainer)
        // Listen for new logs
        // Listen for new logs
        LogManager.onLogListener = { message ->
            runOnUiThread {
                tvLogs.append(message)
                scrollToBottom(svLogsContainer)
            }
        }
    }

    override fun onPause() {
        LogManager.onLogListener = null
        super.onPause()
    }

    private fun scrollToBottom(scrollView: ScrollView) {
        scrollView.post {
            scrollView.smoothScrollTo(0, scrollView.getChildAt(0).height)
        }
    }
}
