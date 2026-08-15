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
    companion object {
        var isRunning = false
    }

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
                
                // Monkeypatch SvcHub.__init__ to capture the reference & thread ID, and redirect stdout/stderr
                try {
                    val globals = py.getModule("builtins").callAttr("dict")
                    py.getModule("builtins").callAttr("exec", """
                        import copyparty.svchub
                        import threading
                        import sys
                        from java import jclass
                        
                        site_packages = "/data/data/nocom.rian.copyparty/files/site-packages"
                        if site_packages not in sys.path:
                            sys.path.insert(0, site_packages)
                            
                        LogManager = jclass("nocom.rian.copyparty.LogManager")
                        
                        class JavaLogRedirector(object):
                            def __init__(self, original):
                                self.original = original
                            def write(self, message):
                                if message:
                                    LogManager.log(message)
                                    self.original.write(message)
                            def flush(self):
                                self.original.flush()
                        
                        if not isinstance(sys.stdout, JavaLogRedirector):
                            sys.stdout = JavaLogRedirector(sys.stdout)
                        if not isinstance(sys.stderr, JavaLogRedirector):
                            sys.stderr = JavaLogRedirector(sys.stderr)
                            
                        if not hasattr(copyparty.svchub, '_original_init'):
                            copyparty.svchub._original_init = copyparty.svchub.SvcHub.__init__
                            
                        def patched_init(self, *args, **kwargs):
                            copyparty.svchub.active_hub = self
                            copyparty.svchub.server_thread_id = threading.get_ident()
                            copyparty.svchub._original_init(self, *args, **kwargs)
                            
                        copyparty.svchub.SvcHub.__init__ = patched_init
                    """.trimIndent(), globals)
                } catch (e: Exception) {
                    Log.e("Copyparty", "Failed to apply SvcHub patch & redirect logs", e)
                }

                val mainModule = py.getModule("copyparty.__main__")
                mainModule.callAttr("main")
            } catch (e: Exception) {
                Log.e("Copyparty", "Error starting server", e)
            }
        }
        serverThread?.start()
        isRunning = true

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val globals = py.getModule("builtins").callAttr("dict")
                py.getModule("builtins").callAttr("exec", """
                    import copyparty.svchub
                    import ctypes
                    
                    if hasattr(copyparty.svchub, 'active_hub') and copyparty.svchub.active_hub is not None:
                        hub = copyparty.svchub.active_hub
                        try:
                            hub.broker.shutdown()
                        except Exception as ex:
                            print("Error shutting down broker: " + str(ex))
                        
                        # Close the listener sockets to unbind the port immediately
                        if hasattr(hub, 'tcpsrv') and hub.tcpsrv is not None:
                            if hasattr(hub.tcpsrv, 'srv') and hub.tcpsrv.srv:
                                for sock in hub.tcpsrv.srv:
                                    try:
                                        sock.close()
                                    except Exception as ex:
                                        print("Error closing socket: " + str(ex))
                                        
                        copyparty.svchub.active_hub = None
                        
                    # Terminate the server thread using async exception
                    if hasattr(copyparty.svchub, 'server_thread_id') and copyparty.svchub.server_thread_id is not None:
                        try:
                            ctypes.pythonapi.PyThreadState_SetAsyncExc(
                                ctypes.c_long(copyparty.svchub.server_thread_id),
                                ctypes.py_object(SystemExit)
                            )
                        except Exception as ex:
                            print("Error killing thread: " + str(ex))
                        copyparty.svchub.server_thread_id = None
                """.trimIndent(), globals)
            }
        } catch (e: Exception) {
            Log.e("Copyparty", "Error during service shutdown", e)
        }
        serverThread?.interrupt()
        isRunning = false
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
