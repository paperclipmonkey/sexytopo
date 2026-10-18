package org.hwyl.sexytopo.demo

/**
 * The chooser, and everything else that brings a file in, lives in `files.js`: see the comment at
 * the top of that file for why it is JavaScript of its own rather than a `js(...)` block here.
 *
 * Whatever is chosen is written straight into the app's own storage under the `BrowserFileStore`
 * key prefix, so from that point on the browser looks exactly like iOS with a file dropped into the
 * Files app, and one shared code path imports both. On iOS Safari the chooser offers iCloud Drive
 * and anything AirDropped, so the browser build can import too.
 */
private fun openPicker(): Boolean =
    js("{ try { return window.sexytopoFiles.pick(); } catch (e) { return false; } }")

private fun takeArrived(): String =
    js("{ try { return window.sexytopoFiles.takeArrived(); } catch (e) { return ''; } }")

actual fun canPickFiles(): Boolean = true

actual fun pickSurveyFile() {
    openPicker()
}

actual fun takeArrivedFiles(): List<String> = takeArrived().split('\n').filter { it.isNotEmpty() }
