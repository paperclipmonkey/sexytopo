package org.hwyl.sexytopo.demo

import kotlinx.coroutines.runBlocking
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.shared.manual.contentsOf
import org.hwyl.sexytopo.shared.manual.parseManual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manual read the way the app reads it: out of the Compose resource bundle, not off disk.
 *
 * `ManualContentTest` reads the file from the source tree, which proves the *content* is right and
 * nothing about whether the app can get at it. This one goes through `Res.readBytes`, the call the
 * running app makes, so a resource that fails to be packaged fails here rather than on the one
 * screen in the app that needs it.
 *
 * The same test exists in `iosTest`, because packaging is per-target and this one cannot answer for
 * that one.
 */
class BundledManualTest {

    @Test
    fun theManualIsInTheResourceBundle() = runBlocking {
        val bytes = Res.readBytes("files/manual.html")
        assertTrue(bytes.size > 20_000, "the bundled manual is ${bytes.size} bytes, not the guide")
        val blocks = parseManual(bytes.decodeToString())
        assertEquals(13, contentsOf(blocks).size, "the bundled manual is not the thirteen sections")
    }
}
