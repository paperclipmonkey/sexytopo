package org.hwyl.sexytopo.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The system clipboard, so an exported survey can be pasted into an email or a notes app.
 *
 * A share sheet would be better and needs an `Activity` to start one from; [AndroidHost] holds the
 * application context on purpose, so that belongs to whichever host app wants it rather than here.
 */
actual fun copyToClipboard(text: String): Boolean {
    val context = AndroidHost.appContext ?: return false
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText("SexyTopo export", text))
    return true
}
