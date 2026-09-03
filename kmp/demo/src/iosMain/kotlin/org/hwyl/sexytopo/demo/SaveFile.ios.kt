package org.hwyl.sexytopo.demo

/**
 * Straight into the Documents directory, which `UIFileSharingEnabled` in `Info.plist` publishes to
 * the Files app. No share sheet, no `UIViewController` to present one from, and the file is where
 * the surveyor can reach it with AirDrop, iCloud or a cable.
 */
actual fun saveTextFile(filename: String, text: String): String? =
    runCatching {
        platformFileStore().writeText(listOf("exports", filename), text)
        "Files › On My iPhone › SexyTopo KMP › exports"
    }.getOrNull()
