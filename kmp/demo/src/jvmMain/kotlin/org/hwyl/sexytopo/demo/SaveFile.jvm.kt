package org.hwyl.sexytopo.demo

import java.io.File

/** The working directory, which is where a desktop run is being driven from anyway. */
actual fun saveTextFile(filename: String, text: String): String? =
    runCatching {
        val file = File(filename).absoluteFile
        // The Therion .xvi can be written into a folder of the surveyor's choosing, which the
        // .th2 names and which may well not exist yet.
        file.parentFile?.mkdirs()
        file.writeText(text)
        file.path
    }.getOrNull()
