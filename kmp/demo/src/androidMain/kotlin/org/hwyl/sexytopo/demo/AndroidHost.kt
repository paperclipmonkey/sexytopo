package org.hwyl.sexytopo.demo

import android.content.Context

/**
 * Where the host app hands Android's `Context` to the shared code: [SurveyLibrary]'s file store is
 * built before the first composition and so can't read `LocalContext`, and the clipboard needs one too.
 *
 * Holds the application context, not the activity's, since this outlives any single activity and an
 * activity context would leak across a rotation.
 */
object AndroidHost {

    internal var appContext: Context? = null
        private set

    fun attach(context: Context) {
        appContext = context.applicationContext
    }
}
