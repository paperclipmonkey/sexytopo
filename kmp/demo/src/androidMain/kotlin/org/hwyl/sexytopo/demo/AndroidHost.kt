package org.hwyl.sexytopo.demo

import android.content.Context

/**
 * Where the host app hands Android's `Context` to the shared code.
 *
 * Two things below this line need one and cannot get it themselves: the file store, which is built
 * from [SurveyLibrary]'s constructor before the first composition and so cannot read
 * `LocalContext`; and the clipboard. An Android library can reach a `Context` unaided only through
 * a `ContentProvider` declared in its manifest or the AndroidX Startup dependency, both of which
 * are more machinery than one line in `MainActivity`.
 *
 * The application context, not the activity's, because this outlives any single activity and
 * holding one would leak it across a rotation.
 *
 * If that line is ever lost, both callers fall back to doing nothing rather than crashing: surveys
 * stay in memory and the app says so the first time a save fails.
 */
object AndroidHost {

    internal var appContext: Context? = null
        private set

    fun attach(context: Context) {
        appContext = context.applicationContext
    }
}
