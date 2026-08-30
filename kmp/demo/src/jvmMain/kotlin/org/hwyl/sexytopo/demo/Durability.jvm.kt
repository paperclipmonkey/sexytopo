package org.hwyl.sexytopo.demo

/**
 * Nothing to ask for: a file written to the app's own storage stays written.
 *
 * The question this answers only exists in a browser — see the expect declaration.
 */
actual fun requestDurableStorage() = Unit

actual fun durabilityWarning(): String? = null
