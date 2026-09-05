package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sizes the Android layout names, held to the ones this port draws with.
 *
 * `dimens.xml` is five lines and only one of them reaches a shared screen — the toolbar's button
 * height, which is the size a thumb has learnt — but a value copied by hand into a constant is a
 * value that drifts when upstream changes its mind, and this is the cheapest possible way to hear
 * about it.
 */
class DimensionParityTest {

    private val dimens = File("../../app/src/main/res/values/dimens.xml").readText()

    private fun dp(name: String): Int =
        Regex("""<dimen name="$name">(\d+)dp</dimen>""").find(dimens)?.groupValues?.get(1)?.toInt()
            ?: error("dimens.xml no longer defines $name")

    @Test
    fun theToolbarIsAsTallAsTheAppMakesIt() {
        assertEquals(dp("toolbar_button_height"), SexyTopoDimens.TOOLBAR_BUTTON_HEIGHT_DP)
    }

    @Test
    fun theToolbarHasAsManyColumnsAsTheLayoutDeclares() {
        val layout = File("../../app/src/main/res/layout/activity_graph.xml").readText()
        val columns = Regex("""android:columnCount="(\d+)"""").find(layout)?.groupValues?.get(1)?.toInt()
        assertEquals(columns, SexyTopoDimens.TOOLBAR_COLUMNS, "activity_graph.xml's GridLayout column count")
    }
}
