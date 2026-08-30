package org.hwyl.sexytopo.demo

import java.io.File

/**
 * The app's external files directory, not the private one the surveys live in: this is the one the
 * Files app and a USB cable can both see, and writing to it needs no permission on any supported
 * Android version.
 */
actual fun saveTextFile(filename: String, text: String): String? {
    val context = AndroidHost.appContext ?: return null
    val directory = context.getExternalFilesDir(null) ?: return null
    return runCatching {
        val file = File(directory, filename)
        file.parentFile?.mkdirs()
        file.writeText(text)
        file.absolutePath
    }.getOrNull()
}
