package nocom.rian.copyparty

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.concurrent.thread

class ServerService : Service() {
    companion object {
        @Volatile
        var isRunning = false
    }

    private var serverThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val configPath = intent?.getStringExtra("CONFIG_PATH")
        if (configPath == null) {
            Log.w("Copyparty", "ServerService started with null config path")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning && serverThread?.isAlive == true) {
            Log.d("Copyparty", "ServerService already running, ignoring start request")
            return START_STICKY
        }
        
        isRunning = true

        serverThread = thread(start = true, name = "CopypartyServerThread") {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }

                // ponytail: symlink bundled ffmpeg/ffprobe so copyparty finds them on PATH
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val binDir = File(filesDir, "bin").apply { mkdirs() }
                for (tool in arrayOf("ffmpeg", "ffprobe")) {
                    val src = File(nativeLibDir, "lib$tool.so")
                    val link = File(binDir, tool)
                    // recreate if stale (e.g. app update changed nativeLibDir)
                    if (link.exists() && !src.exists()) link.delete()
                    if (!link.exists() && src.exists()) {
                        try {
                            Os.symlink(src.absolutePath, link.absolutePath)
                            Log.i("Copyparty", "$tool linked: ${src.absolutePath}")
                        } catch (e: Exception) {
                            Log.w("Copyparty", "Failed to symlink $tool", e)
                        }
                    } else if (!src.exists()) {
                        Log.w("Copyparty", "$tool not found at ${src.absolutePath}")
                    }
                }

                val py = Python.getInstance()
                val sys = py.getModule("sys")
                sys.put("argv", arrayOf("copyparty", "-c", configPath, "--sig-thr"))

                val globals = py.getModule("builtins").callAttr("dict")
                py.getModule("builtins").callAttr("exec", """
                    import sys
                    import importlib
                    import os
                    import signal
                    import threading
                    from java import jclass

                    # prepend bundled ffmpeg/ffprobe bin dir to PATH
                    _bin = "${binDir.absolutePath}"
                    if os.path.isdir(_bin):
                        os.environ["PATH"] = _bin + os.pathsep + os.environ.get("PATH", "")

                    target_dir = os.path.join(sys.prefix, "files", "site-packages") if hasattr(sys, "prefix") else ""
                    if os.path.exists(target_dir) and target_dir not in sys.path:
                        sys.path.insert(0, target_dir)

                    importlib.invalidate_caches()
                    if hasattr(sys, 'path_importer_cache'):
                        sys.path_importer_cache.clear()

                    LogManager = jclass("nocom.rian.copyparty.LogManager")

                    class _LogRedirector:
                        def __init__(self, orig):
                            self._orig = orig
                            self.encoding = getattr(orig, 'encoding', 'utf-8')
                        def write(self, s):
                            if s:
                                LogManager.log(s)
                                self._orig.write(s)
                        def flush(self):
                            self._orig.flush()
                        def isatty(self):
                            return False
                        def __getattr__(self, name):
                            return getattr(self._orig, name)

                    if not isinstance(sys.stdout, _LogRedirector):
                        sys.stdout = _LogRedirector(sys.stdout)
                    if not isinstance(sys.stderr, _LogRedirector):
                        sys.stderr = _LogRedirector(sys.stderr)

                    signal.signal = lambda *a, **kw: None

                    import socket
                    _orig_sock_init = getattr(socket.socket, '_orig_sock_init', socket.socket.__init__)
                    socket.socket._orig_sock_init = _orig_sock_init

                    def _patched_socket_init(self, family=socket.AF_INET, type=socket.SOCK_STREAM, proto=0, fileno=None):
                        _orig_sock_init(self, family, type, proto, fileno)
                        try:
                            self.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                            if hasattr(socket, 'SO_REUSEPORT'):
                                self.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                        except Exception:
                            pass

                    socket.socket.__init__ = _patched_socket_init

                    for _mod in list(sys.modules):
                        if _mod.startswith('copyparty'):
                            del sys.modules[_mod]

                    import copyparty.svchub
                    _orig_hub_init = getattr(copyparty.svchub.SvcHub, '_orig_hub_init', copyparty.svchub.SvcHub.__init__)
                    copyparty.svchub.SvcHub._orig_hub_init = _orig_hub_init
                    def _patched_init(self, *a, **kw):
                        _orig_hub_init(self, *a, **kw)
                        copyparty.svchub.active_hub = self
                    copyparty.svchub.SvcHub.__init__ = _patched_init

                """.trimIndent(), globals)

                py.getModule("copyparty.__main__").callAttr("main")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("SystemExit")) {
                    Log.d("Copyparty", "Server main thread exited cleanly via SystemExit")
                } else {
                    Log.e("Copyparty", "Error starting server", e)
                    LogManager.log("SERVER ERROR: ${e.message}\n")
                }
            } finally {
                isRunning = false
                Log.d("Copyparty", "Server thread terminated cleanly.")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val globals = py.getModule("builtins").callAttr("dict")
                py.getModule("builtins").callAttr("exec", """
                    import copyparty.svchub

                    hub = getattr(copyparty.svchub, 'active_hub', None)
                    if hub is not None:
                        try:
                            hub.shutdown()
                        except Exception:
                            pass
                        copyparty.svchub.active_hub = None
                """.trimIndent(), globals)
            }
        } catch (e: Exception) {
            Log.e("Copyparty", "Error during service shutdown", e)
        }

        try {
            serverThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w("Copyparty", "Interrupted waiting for server thread termination", e)
        }

        serverThread = null
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