package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two things a browser tab cannot tell you on its own: that it is running the build that was
 * actually deployed, and that something is happening while it finds out.
 *
 * Neither is testable from the JVM in the way `field.mjs` tests a running page - there is no
 * browser here. What *is* testable without one is that the wiring these two fixes depend on has
 * not quietly come apart: the placeholder `sw.js` needs Gradle to fill in, the code path that
 * turns it into an actual cache-namespace bump, and the handful of lines in `index.html` that make
 * a canvas appearing the signal to take the loading screen away and a `controllerchange` the
 * signal to offer a reload. A grep cannot confirm any of this *works*, but it can catch the next
 * edit that removes a line one of those two depend on without anyone running a browser to notice.
 */
class WasmBootstrapTest {

    private val resources = File("src/wasmJsMain/resources")
    private val serviceWorker = File(resources, "sw.js").readText()
    private val page = File(resources, "index.html").readText()
    private val buildScript = File("build.gradle.kts").readText()

    @Test
    fun theCacheNameCarriesAPlaceholderForTheBuild() {
        assertTrue(
            "const CACHE = 'sexytopo-%%BUILD_ID%%'" in serviceWorker,
            "sw.js's CACHE is no longer a %%BUILD_ID%% placeholder - a hard-coded name means every " +
                "deploy reuses the same cache namespace, which is the staleness this exists to fix",
        )
    }

    @Test
    fun theBuildFillsInThePlaceholderItLeftInServiceWorkerJs() {
        assertTrue(
            "serviceWorkerBuildId" in buildScript && "%%BUILD_ID%%" in buildScript,
            "build.gradle.kts no longer wires a build id into sw.js's %%BUILD_ID%% placeholder",
        )
        assertTrue(
            "wasmJsProcessResources" in buildScript,
            "the substitution has to run as part of processing wasmJs resources, or dist output " +
                "ships the literal placeholder",
        )
    }

    @Test
    fun coreAssetsBypassTheBrowsersOwnHttpCacheOnInstall() {
        assertTrue(
            "cache: 'reload'" in serviceWorker,
            "install-time CORE caching no longer forces a real network fetch - within a host's " +
                "cache lifetime this can precache whatever index.html the browser had lying around " +
                "from the build being replaced, not the one just deployed",
        )
    }

    @Test
    fun theWorkerStillTakesOverOpenTabsWithoutWaitingForThemToClose() {
        // Without these two, the update sits in a 'waiting' worker until every tab closes.
        assertTrue("self.skipWaiting()" in serviceWorker, "sw.js no longer calls skipWaiting()")
        assertTrue("self.clients.claim()" in serviceWorker, "sw.js no longer calls clients.claim()")
    }

    @Test
    fun thePageOffersAReloadRatherThanForcingOneOnAGenuineUpdate() {
        assertTrue(
            "addEventListener('controllerchange'" in page,
            "index.html no longer listens for controllerchange, so a tab left open across a " +
                "deploy has no way to learn it is now running the old build",
        )
        assertTrue(
            "hadController" in page,
            "the controllerchange handler has to tell a genuine update apart from a tab's own " +
                "first install claiming it, or every first visit shows \"a new version is ready\"",
        )
        assertTrue(
            "button.addEventListener('click', function () { window.location.reload() })" in page,
            "the reload is no longer wired to the toast's own button - see the surveyor-mid-sketch " +
                "reasoning in index.html's own comment for why nothing should reload on its own",
        )
    }

    @Test
    fun thePageAsksForAnUpdateCheckRatherThanWaitingOnTheBrowsersOwnSchedule() {
        assertTrue(
            "registration.update()" in page,
            "nothing calls registration.update(), so an update is only ever noticed on whatever " +
                "schedule the browser applies on its own - up to 24 hours",
        )
        assertTrue(
            "setInterval(checkForUpdate" in page,
            "a session that never backgrounds the tab - which the wake lock above exists to " +
                "encourage - only gets the one check on load without a periodic fallback",
        )
    }

    @Test
    fun theLoadingOverlayIsInTheInitialMarkupNotAddedByScript() {
        // Has to be literally in the HTML the server sends, not created by JavaScript.
        val beforeFirstScript = page.substringBefore("<script")
        assertTrue(
            "id=\"loading-overlay\"" in beforeFirstScript,
            "the loading overlay is not present before the first <script> tag, so it would not " +
                "actually be visible during the download this exists to cover",
        )
    }

    @Test
    fun theOverlayIsDismissedByTheCanvasAppearingNotByATimer() {
        assertTrue(
            "querySelector('canvas')" in page && "MutationObserver" in page,
            "the overlay's dismissal no longer watches for the canvas Compose creates - a fixed " +
                "delay would either flash real content or hide a canvas that never actually drew",
        )
    }
}
