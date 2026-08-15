package nocom.rian.copyparty

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class ServerService : Service() {

    private var serverThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configPath = intent?.getStringExtra("CONFIG_PATH") ?: return START_NOT_STICKY

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "copyparty_channel")
            .setContentTitle("Copyparty Server")
            .setContentText("Serving files in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        serverThread = Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
                
                val py = Python.getInstance()
                val pyArgs = arrayOf("copyparty", "-c", configPath)
                
                val sys = py.getModule("sys")
                sys.put("argv", pyArgs)
                
                val mainModule = py.getModule("copyparty.__main__")
                try {
                    mainModule.callAttr("main", pyArgs)
                } catch (e: Exception) {
                    if (e.message?.contains("positional argument") == true || e.message?.contains("takes 0 positional") == true) {
                        mainModule.callAttr("main")
                    } else {
                        throw e
                    }
                }
            } catch (e: Exception) {
                Log.e("Copyparty", "Error starting server", e)
            }
        }
        serverThread?.start()

        return START_STICKY
    }

    override fun onDestroy() {
        serverThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "copyparty_channel",
                "Copyparty Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
