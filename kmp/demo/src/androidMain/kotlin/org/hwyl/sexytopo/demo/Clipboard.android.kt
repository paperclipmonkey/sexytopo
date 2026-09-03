package org.hwyl.sexytopo.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The system clipboard, so an exported survey can be pasted into an email or a notes app.
 *
 * A share sheet would be better but needs an `Activity`, which [AndroidHost] deliberately avoids holding.
 */
actual fun copyToClipboard(text: String): Boolean {
    val context = AndroidHost.appContext ?: return false
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("SexyTopo export", text))
    return true
}
