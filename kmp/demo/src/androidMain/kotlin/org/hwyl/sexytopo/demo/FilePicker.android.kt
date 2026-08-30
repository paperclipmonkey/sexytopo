package org.hwyl.sexytopo.demo

/** Android's picker needs an Activity to launch from; the app's own folder is visible instead. */
actual fun canPickFiles(): Boolean = false

actual fun pickSurveyFile() = Unit
