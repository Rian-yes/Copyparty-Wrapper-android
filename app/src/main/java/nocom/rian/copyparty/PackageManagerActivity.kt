package nocom.rian.copyparty

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class PackageManagerActivity : AppCompatActivity() {

    private val packagesList = mutableListOf<PythonPackage>()
    private lateinit var adapter: PackageAdapter
    private lateinit var lvPackages: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_package_manager)

        val etPackageName = findViewById<EditText>(R.id.etPackageName)
        val btnInstallPackage = findViewById<Button>(R.id.btnInstallPackage)
        lvPackages = findViewById(R.id.lvPackages)

        adapter = PackageAdapter(this, packagesList) { pkg ->
            uninstallPackage(pkg.name)
        }
        lvPackages.adapter = adapter

        btnInstallPackage.setOnClickListener {
            val packageName = etPackageName.text.toString().trim()
            if (packageName.isNotEmpty()) {
                installPackage(packageName)
                etPackageName.text.clear()
            } else {
                Toast.makeText(this, "Package name cannot be empty!", Toast.LENGTH_SHORT).show()
            }
        }

        val llSuggestions = findViewById<LinearLayout>(R.id.llSuggestions)
        val optionalModules = listOf(
            "Pillow", "mutagen", "pyvips", "pillow-heif", "pillow-avif-plugin",
            "rawpy", "pyzmq", "impacket", "python-magic", "paramiko",
            "partftpy", "psutil", "pyftpdlib", "argon2-cffi", "pyopenssl"
        )
        for (module in optionalModules) {
            val button = Button(this).apply {
                text = module
                isAllCaps = false
                textSize = 12f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 8, 0)
                }
                layoutParams = params
                setOnClickListener {
                    etPackageName.setText(module)
                }
            }
            llSuggestions.addView(button)
        }

        refreshPackagesList()
    }

    private fun refreshPackagesList() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        val py = Python.getInstance()

        try {
            val targetDir = File(filesDir, "site-packages").absolutePath

            val pythonCode = """
import os
import sys
import importlib
import importlib.util
import importlib.metadata
import types

def is_in_site_packages(pkg_name, target_dir):
    if not os.path.exists(target_dir):
        return False
    clean = pkg_name.lower().replace('-', '_')
    try:
        for item in os.listdir(target_dir):
            item_clean = item.lower().replace('-', '_')
            if (item_clean == clean or 
                item_clean.startswith(clean + '-') or 
                item_clean.startswith(clean + '.') or 
                item_clean == clean + '.py'):
                return True
    except Exception:
        pass
    return False

def get_packages(site_packages_dir):
    if not os.path.exists(site_packages_dir):
        try:
            os.makedirs(site_packages_dir, exist_ok=True)
        except Exception:
            pass

    if site_packages_dir not in sys.path:
        sys.path.insert(0, site_packages_dir)
    importlib.invalidate_caches()

    pkgs = []
    seen = set()

    # 1. Scan site-packages directory directly
    if os.path.exists(site_packages_dir):
        try:
            items = os.listdir(site_packages_dir)
            dist_info_items = [i for i in items if i.endswith('.dist-info') or i.endswith('.egg-info')]
            other_items = [i for i in items if i not in dist_info_items]

            for item in dist_info_items:
                item_path = os.path.join(site_packages_dir, item)
                meta_path = os.path.join(item_path, 'METADATA')
                if not os.path.exists(meta_path):
                    meta_path = os.path.join(item_path, 'PKG-INFO')
                pkg_name = None
                pkg_ver = 'installed'
                if os.path.exists(meta_path):
                    with open(meta_path, 'r', encoding='utf-8', errors='ignore') as f:
                        for line in f:
                            if line.startswith('Name:'):
                                pkg_name = line.split(':', 1)[1].strip()
                            elif line.startswith('Version:'):
                                pkg_ver = line.split(':', 1)[1].strip()
                if not pkg_name:
                    pkg_name = item.rsplit('.', 1)[0].replace('.dist-info', '').replace('.egg-info', '')
                if pkg_name and pkg_name.lower() not in seen:
                    seen.add(pkg_name.lower())
                    pkgs.append((pkg_name, pkg_ver, True))

            for item in other_items:
                if item.startswith('.') or item.startswith('__'):
                    continue
                item_path = os.path.join(site_packages_dir, item)
                if os.path.isdir(item_path) or item.endswith('.py'):
                    pkg_name = item[:-3] if item.endswith('.py') else item
                    if pkg_name and pkg_name.lower() not in seen:
                        seen.add(pkg_name.lower())
                        pkgs.append((pkg_name, 'installed', True))
        except Exception:
            pass

    # 2. Check importlib metadata distributions
    try:
        for dist in importlib.metadata.distributions():
            try:
                name = dist.name if hasattr(dist, 'name') and dist.name else (dist.metadata.get('Name') if dist.metadata else None)
                if not name:
                    continue
                lname = name.lower()
                version = dist.version if hasattr(dist, 'version') and dist.version else (dist.metadata.get('Version') if dist.metadata else 'pre-installed')
                
                is_custom = is_in_site_packages(name, site_packages_dir)
                if lname not in seen:
                    seen.add(lname)
                    pkgs.append((name, str(version), is_custom))
            except Exception:
                continue
    except Exception:
        pass

    # 3. Known pre-installed fallback
    known_preinstalled = [
        ("copyparty", "copyparty"),
        ("Pillow", "PIL"),
        ("mutagen", "mutagen"),
        ("jinja2", "jinja2"),
        ("requests", "requests"),
        ("urllib3", "urllib3"),
        ("certifi", "certifi"),
        ("six", "six"),
        ("setuptools", "setuptools"),
        ("pip", "pip")
    ]
    for disp_name, mod_name in known_preinstalled:
        if disp_name.lower() not in seen:
            try:
                if importlib.util.find_spec(mod_name) is not None:
                    seen.add(disp_name.lower())
                    ver = "pre-installed"
                    try:
                        m = importlib.import_module(mod_name)
                        ver = getattr(m, '__version__', getattr(m, 'VERSION', 'pre-installed'))
                    except Exception:
                        pass
                    is_custom = is_in_site_packages(disp_name, site_packages_dir)
                    pkgs.append((disp_name, str(ver), is_custom))
            except Exception:
                pass

    return pkgs
            """.trimIndent()

            val sysMod = py.getModule("sys")
            val modulesDict = sysMod.get("modules")
            var pkgHelper = modulesDict?.callAttr("get", "pkg_helper")
            if (pkgHelper == null) {
                val typesMod = py.getModule("types")
                pkgHelper = typesMod.callAttr("ModuleType", "pkg_helper")
                modulesDict?.callAttr("__setitem__", "pkg_helper", pkgHelper)
            }
            py.getModule("builtins").callAttr("exec", pythonCode, pkgHelper?.get("__dict__"))

            val pyPackages = pkgHelper?.callAttr("get_packages", targetDir)
            packagesList.clear()
            if (pyPackages != null) {
                for (pyPkg in pyPackages.asList()) {
                    val tuple = pyPkg.asList()
                    val name = tuple[0].toString()
                    val version = tuple[1].toString()
                    val isCustom = tuple[2].toBoolean()
                    packagesList.add(PythonPackage(name, version, isCustom))
                }
            }

            packagesList.sortBy { it.name.lowercase() }
            adapter.notifyDataSetChanged()
            LogManager.log("[Package Manager] Listed ${packagesList.size} package(s).")
        } catch (e: Exception) {
            Log.e("Copyparty", "Failed to list packages", e)
            LogManager.log("[Package Manager] Error listing packages: ${e.message}")
            Toast.makeText(this, "Failed to list packages: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun installPackage(packageName: String) {
        val targetDir = File(filesDir, "site-packages").absolutePath
        runPipCommand(arrayOf("install", "--target", targetDir, packageName))
    }

    private fun setUiEnabled(enabled: Boolean) {
        runOnUiThread {
            findViewById<Button>(R.id.btnInstallPackage).isEnabled = enabled
            lvPackages.isEnabled = enabled
        }
    }

    private fun uninstallPackage(packageName: String) {
        val targetDir = File(filesDir, "site-packages")
        val cleanName = packageName.lowercase().replace('-', '_')

        // 1. Delete files from disk
        if (targetDir.exists()) {
            targetDir.listFiles()?.forEach { file ->
                val fName = file.name.lowercase().replace('-', '_')
                if (fName == cleanName || fName.startsWith("${cleanName}-") || 
                    fName.startsWith("${cleanName}.") || fName == "${cleanName}.py") {
                    file.deleteRecursively()
                }
            }
        }

        // 2. Unload from Python memory & invalidate cache
        try {
            val py = Python.getInstance()
            val globals = py.getModule("builtins").callAttr("dict")
            globals.put("pkg_to_remove", cleanName)

            py.getModule("builtins").callAttr("exec", """
                import sys, importlib
                
                # Remove all loaded submodules from memory
                to_remove = [mod for mod in sys.modules if mod == pkg_to_remove or mod.startswith(pkg_to_remove + ".")]
                for mod in to_remove:
                    sys.modules.pop(mod, None)
                    
                importlib.invalidate_caches()
                if hasattr(sys, 'path_importer_cache'):
                    sys.path_importer_cache.clear()
            """.trimIndent(), globals)
        } catch (e: Exception) {
            Log.e("Copyparty", "Error unloading module from memory", e)
        }

        refreshPackagesList()
        Toast.makeText(this, "$packageName uninstalled", Toast.LENGTH_SHORT).show()
    }

    private fun runPipCommand(commandArgs: Array<String>) {
        val context = this
        Toast.makeText(context, "Running pip command... Check Log Viewer for details.", Toast.LENGTH_LONG).show()

        setUiEnabled(false)
        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }

                val py = Python.getInstance()
                val targetDir = File(filesDir, "site-packages").absolutePath

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
    except SystemExit as e:
        if e.code == 0:
            LogManager.log("\n[PIP] Operation completed successfully.\n")
        else:
            LogManager.log(f"\n[PIP] Operation failed with exit code: {e.code}\n")
    except Exception as ex:
        LogManager.log(f"\n[PIP] Error: {ex}\n")
    finally:
        sys.argv = old_argv
        sys.stdout = old_stdout
        sys.stderr = old_stderr
                """.trimIndent()

                // Register module safely inside sys.modules
                val sysMod = py.getModule("sys")
                val modulesDict = sysMod.get("modules")
                var pipHelper = modulesDict?.callAttr("get", "pip_runner")
                if (pipHelper == null) {
                    val typesMod = py.getModule("types")
                    pipHelper = typesMod.callAttr("ModuleType", "pip_runner")
                    modulesDict?.callAttr("__setitem__", "pip_runner", pipHelper)
                }

                py.getModule("builtins").callAttr("exec", pipRunnerCode, pipHelper?.get("__dict__"))

                // Invoke directly passing arrays/strings as parameters
                pipHelper?.callAttr("execute_pip", commandArgs, targetDir)

            } catch (e: Exception) {
                Log.e("Copyparty", "Failed to run pip", e)
                runOnUiThread {
                    Toast.makeText(context, "Pip error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    setUiEnabled(true)
                    refreshPackagesList()
                }
            }
        }.start()
    }
}

data class PythonPackage(val name: String, val version: String, val isCustom: Boolean)

class PackageAdapter(
    private val context: Context,
    private val dataSource: List<PythonPackage>,
    private val onUninstallClick: (PythonPackage) -> Unit
) : BaseAdapter() {

    private val inflater: LayoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

    override fun getCount(): Int = dataSource.size

    override fun getItem(position: Int): Any = dataSource[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val rowView = convertView ?: inflater.inflate(R.layout.list_item_package, parent, false)

        val tvPackageInfo = rowView.findViewById<TextView>(R.id.tvPackageInfo)
        val btnUninstallPackage = rowView.findViewById<TextView>(R.id.btnUninstallPackage)

        val pkg = getItem(position) as PythonPackage
        tvPackageInfo.text = "${pkg.name} (${pkg.version})"

        if (pkg.isCustom) {
            btnUninstallPackage.visibility = View.VISIBLE
            btnUninstallPackage.isEnabled = true
            btnUninstallPackage.setOnClickListener {
                onUninstallClick(pkg)
            }
        } else {
            btnUninstallPackage.visibility = View.GONE
            btnUninstallPackage.isEnabled = false
        }
        return rowView
    }
}
