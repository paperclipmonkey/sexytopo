package org.hwyl.sexytopo.demo

import platform.UIKit.UIPasteboard

/**
 * The system pasteboard, so an exported survey can be pasted into Mail, Notes or Files.
 *
 * A share sheet would be better but needs a `UIViewController` to present from, which this layer
 * does not have.
 */
actual fun copyToClipboard(text: String): Boolean {
    UIPasteboard.generalPasteboard.string = text
    return true
}
