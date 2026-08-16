package nocom.rian.copyparty

import java.util.ArrayList

object LogManager {
    private val logs = ArrayList<String>()
    
    @Volatile
    var onLogListener: ((String) -> Unit)? = null
    
    @JvmStatic
    fun log(message: String) {
        synchronized(logs) {
            logs.add(message)
            if (logs.size > 1500) {
                logs.removeAt(0)
            }
        }
        android.util.Log.d("Copyparty", message)
        onLogListener?.invoke(message)
    }
    
    @JvmStatic
    fun getLogs(): List<String> {
        synchronized(logs) {
            return ArrayList(logs)
        }
    }
    
    @JvmStatic
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
