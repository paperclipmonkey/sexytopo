package org.hwyl.sexytopo.demo

import java.io.File

actual fun saveBinaryFile(filename: String, bytes: ByteArray): String? {
    val context = AndroidHost.appContext ?: return null
    val directory = context.getExternalFilesDir(null) ?: return null
    return runCatching {
        val file = File(directory, filename)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()
}
