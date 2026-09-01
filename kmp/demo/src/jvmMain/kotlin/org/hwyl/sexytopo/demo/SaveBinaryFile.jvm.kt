package org.hwyl.sexytopo.demo

import java.io.File

actual fun saveBinaryFile(filename: String, bytes: ByteArray): String? =
    runCatching {
        val file = File(filename).absoluteFile
        file.writeBytes(bytes)
        file.path
    }.getOrNull()
