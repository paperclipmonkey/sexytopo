package org.hwyl.sexytopo.demo

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Into the app's own Documents directory, where the Files app can reach it — the same place
 * [saveTextFile] puts an export, and the same sentence back, so the two read alike in the UI.
 *
 * The `NSData` construction is copied deliberately from `CoreBluetoothTransport.toNSData`, which
 * is the one piece of this kind that a macOS runner has actually compiled. Both opt-ins are needed
 * and are easy to leave off: `ExperimentalForeignApi` for the pinning and `BetaInteropApi` for
 * `NSData.create` itself. Nothing on Linux says so — `compileKotlinIosSimulatorArm64` is SKIPPED
 * here rather than run — so the check that matters for this file is the macOS job in CI.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun saveBinaryFile(filename: String, bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val documents =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return null
        val directory = "$documents/exports"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)

        val data =
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        if (!data.writeToFile("$directory/$filename", true)) return null
        "Files › On My iPhone › SexyTopo KMP › exports"
    }.getOrNull()
}
