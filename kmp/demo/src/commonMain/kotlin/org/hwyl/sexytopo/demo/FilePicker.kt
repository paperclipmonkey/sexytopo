package org.hwyl.sexytopo.demo

/**
 * Whether this platform can offer a file chooser. True only in the browser — on iOS and Android
 * the app's own folder is visible in the system file manager, so putting a survey in is a drag
 * rather than a dialog.
 */
expect fun canPickFiles(): Boolean

/**
 * Opens the browser's file chooser and copies whatever is chosen into the app's storage root.
 * Fire-and-forget, since the chooser is asynchronous and browser-native.
 */
expect fun pickSurveyFile()
