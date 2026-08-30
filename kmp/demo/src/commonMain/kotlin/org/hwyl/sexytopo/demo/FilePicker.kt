package org.hwyl.sexytopo.demo

/**
 * Whether this platform can offer a file chooser.
 *
 * True only in the browser. iOS and Android both have a document picker, and both need a
 * `UIViewController` or an `Activity` to present it from — neither of which this layer has, and
 * neither of which it needs: on both, the app's own folder is visible in the system file manager,
 * so putting a survey *into* the app is a drag rather than a dialog.
 */
expect fun canPickFiles(): Boolean

/**
 * Opens the browser's file chooser and copies whatever is chosen into the app's storage root,
 * where [SurveyImport.candidates] will find it.
 *
 * Fire-and-forget, because the chooser is asynchronous and browser-native: there is no callback to
 * wait on that would not make this whole call chain suspending. The import dialog re-reads the
 * candidate list while it is open, so the file appears a moment after it is picked.
 */
expect fun pickSurveyFile()
