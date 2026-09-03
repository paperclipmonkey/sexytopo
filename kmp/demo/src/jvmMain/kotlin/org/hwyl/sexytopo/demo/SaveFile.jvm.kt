package org.hwyl.sexytopo.demo

import java.io.File

/** The working directory, which is where a desktop run is being driven from anyway. */
actual fun saveTextFile(filename: String, text: String): String? =
    runCatching {
        val file = File(filename).absoluteFile
        file.writeText(text)
        file.path
    }.getOrNull()
