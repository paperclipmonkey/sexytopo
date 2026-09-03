package org.hwyl.sexytopo.demo

/**
 * `UIDocumentPickerViewController` needs a view controller to present from, which this layer does
 * not have. It does not need one either: `UIFileSharingEnabled` puts the app's Documents directory
 * in the Files app, so a survey is imported by dropping it there.
 */
actual fun canPickFiles(): Boolean = false

actual fun pickSurveyFile() = Unit
