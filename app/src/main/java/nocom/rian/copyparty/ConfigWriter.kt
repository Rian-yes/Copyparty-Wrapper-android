package nocom.rian.copyparty

import java.io.File

class ConfigWriter {
    companion object {
        fun generateConfig(
            httpPort: String,
            enableFtp: Boolean,
            ftpPort: String,
            sharedPath: String,
            uploadHook: String,
            outputFile: File
        ) {
            val sb = StringBuilder()
            // Global section
            sb.appendLine("[global]")
            sb.appendLine("  p: ${httpPort.ifBlank { "3923" }}")
            if (enableFtp) {
                sb.appendLine("  ftp: ${ftpPort.ifBlank { "2121" }}")
            }
            if (uploadHook.isNotBlank()) {
                sb.appendLine("  xbu: $uploadHook")
            }
            sb.appendLine()

            // Root volume section
            sb.appendLine("[/]")
            sb.appendLine("  ${sharedPath.ifBlank { "/sdcard/Download" }}")
            sb.appendLine("  accs:")
            sb.appendLine("    A: *")

            outputFile.writeText(sb.toString())
        }
    }
}
