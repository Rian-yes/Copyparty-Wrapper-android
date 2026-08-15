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

        refreshPackagesList()
    }

    private fun refreshPackagesList() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }
        val py = Python.getInstance()
        val globals = py.getModule("builtins").callAttr("dict")
        
        try {
            val targetDir = File(filesDir, "site-packages").absolutePath
            globals.put("site_packages_dir", targetDir)

            py.getModule("builtins").callAttr("exec", """
                import importlib.metadata
                import sys
                
                def get_packages(site_packages_dir):
                    if site_packages_dir not in sys.path:
                        sys.path.insert(0, site_packages_dir)
                    pkgs = []
                    for dist in importlib.metadata.distributions():
                        name = dist.metadata['Name']
                        if name.lower() == 'copyparty':
                            continue
                        try:
                            path = str(dist.locate_file(''))
                            is_custom = site_packages_dir in path
                        except:
                            is_custom = False
                        pkgs.append({
                            "name": name,
                            "version": dist.version,
                            "is_custom": is_custom
                        })
                    return pkgs
            """.trimIndent(), globals)

            val pyPackages = py.getModule("builtins").callAttr("get_packages", targetDir)
            val packages = pyPackages.asList()
            
            packagesList.clear()
            for (pyPkg in packages) {
                val pkgMap = pyPkg.asMap()
                val name = pkgMap[py.getModule("builtins").callAttr("str", "name")]?.toString() ?: ""
                val version = pkgMap[py.getModule("builtins").callAttr("str", "version")]?.toString() ?: ""
                val isCustom = pkgMap[py.getModule("builtins").callAttr("str", "is_custom")]?.toBoolean() ?: false
                packagesList.add(PythonPackage(name, version, isCustom))
            }
            
            packagesList.sortBy { it.name.lowercase() }
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Log.e("Copyparty", "Failed to list packages", e)
            Toast.makeText(this, "Failed to list packages: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installPackage(packageName: String) {
        val targetDir = File(filesDir, "site-packages").absolutePath
        runPipCommand(arrayOf("install", "--target", targetDir, packageName))
    }

    private fun uninstallPackage(packageName: String) {
        runPipCommand(arrayOf("uninstall", "-y", packageName))
    }

    private fun runPipCommand(commandArgs: Array<String>) {
        val context = this
        Toast.makeText(context, "Running pip command... Check Log Viewer for details.", Toast.LENGTH_LONG).show()

        Thread {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }

                val py = Python.getInstance()
                val globals = py.getModule("builtins").callAttr("dict")

                val targetDir = File(filesDir, "site-packages").absolutePath
                
                py.getModule("builtins").callAttr("exec", """
                    import sys
                    import runpy
                    from java import jclass
                    
                    LogManager = jclass("nocom.rian.copyparty.LogManager")
                    
                    def run_pip(args, target_path):
                        if target_path not in sys.path:
                            sys.path.insert(0, target_path)
                            
                        old_argv = sys.argv
                        old_stdout = sys.stdout
                        old_stderr = sys.stderr
                        
                        class PipOutputRedirector(object):
                            def write(self, text):
                                if text:
                                    LogManager.log(text)
                            def flush(self):
                                pass
                                
                        sys.stdout = PipOutputRedirector()
                        sys.stderr = PipOutputRedirector()
                        
                        try:
                            LogManager.log("[PIP] Running command: pip " + " ".join(args) + "\n")
                            sys.argv = ["pip"] + args
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
                            
                """.trimIndent(), globals)

                val sysArgs = py.getModule("builtins").callAttr("list")
                for (arg in commandArgs) {
                    sysArgs.callAttr("append", arg)
                }

                globals.put("sys_args", sysArgs)
                globals.put("target_path", targetDir)

                py.getModule("builtins").callAttr("exec", "run_pip(sys_args, target_path)", globals)

                runOnUiThread {
                    refreshPackagesList()
                }

            } catch (e: Exception) {
                Log.e("Copyparty", "Failed to run pip", e)
                runOnUiThread {
                    Toast.makeText(context, "Pip error: ${e.message}", Toast.LENGTH_LONG).show()
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
        val btnUninstallPackage = rowView.findViewById<Button>(R.id.btnUninstallPackage)

        val pkg = getItem(position) as PythonPackage
        tvPackageInfo.text = "${pkg.name} (${pkg.version})"

        if (pkg.isCustom) {
            btnUninstallPackage.visibility = View.VISIBLE
            btnUninstallPackage.isEnabled = true
            btnUninstallPackage.setOnClickListener {
                onUninstallClick(pkg)
            }
        } else {
            btnUninstallPackage.visibility = View.INVISIBLE
            btnUninstallPackage.isEnabled = false
        }

        return rowView
    }
}
