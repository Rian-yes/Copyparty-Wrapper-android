package nocom.rian.copyparty

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

object PipRunner {
    fun run(context: Context, commandArgs: Array<String>, callback: (Boolean) -> Unit) {
        Thread {
            var success = false
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }

                val py = Python.getInstance()
                val targetDir = File(context.filesDir, "site-packages").absolutePath

                val pipRunnerCode = """
import sys
import runpy
import importlib
from java import jclass

LogManager = jclass("nocom.rian.copyparty.LogManager")

def execute_pip(args, target_path):
    if target_path not in sys.path:
        sys.path.insert(0, target_path)
    importlib.invalidate_caches()
    if hasattr(sys, 'path_importer_cache'):
        sys.path_importer_cache.clear()
        
    old_argv = sys.argv
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    
    class PipOutputRedirector(object):
        def write(self, text):
            if text:
                LogManager.log(text)
        def flush(self):
            pass
        def isatty(self):
            return False
    sys.stdout = PipOutputRedirector()
    sys.stderr = PipOutputRedirector()
    
    try:
        LogManager.log("[PIP] Running command: pip " + " ".join(args) + "\n")
        sys.argv = ["pip"] + list(args)
        runpy.run_module("pip", run_name="__main__")
        return True
    except SystemExit as e:
        if e.code == 0:
            LogManager.log("\n[PIP] Operation completed successfully.\n")
            return True
        else:
            LogManager.log(f"\n[PIP] Operation failed with exit code: {e.code}\n")
            return False
    except Exception as ex:
        LogManager.log(f"\n[PIP] Error: {ex}\n")
        return False
    finally:
        sys.argv = old_argv
        sys.stdout = old_stdout
        sys.stderr = old_stderr
                """.trimIndent()

                val sysMod = py.getModule("sys")
                val modulesDict = sysMod.get("modules")
                var pipHelper = modulesDict?.callAttr("get", "pip_runner")
                if (pipHelper == null) {
                    val typesMod = py.getModule("types")
                    pipHelper = typesMod.callAttr("ModuleType", "pip_runner")
                    modulesDict?.callAttr("__setitem__", "pip_runner", pipHelper)
                }

                py.getModule("builtins").callAttr("exec", pipRunnerCode, pipHelper?.get("__dict__"))
                val res = pipHelper?.callAttr("execute_pip", commandArgs, targetDir)
                success = res?.toBoolean() ?: false
            } catch (e: Exception) {
                Log.e("Copyparty", "Failed to run pip", e)
                LogManager.log("[PIP] Error: ${e.message}\n")
            } finally {
                callback(success)
            }
        }.start()
    }
}
