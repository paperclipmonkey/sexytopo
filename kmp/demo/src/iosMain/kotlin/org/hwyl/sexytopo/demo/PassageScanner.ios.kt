package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignmentGravityAndHeading
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTimer
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UILabel
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.darwin.sel_registerName

/**
 * Scanning the shape of a passage with ARKit.
 *
 * The surveyor stands at the station, opens this, and sweeps the phone round the passage. ARKit
 * tracks where the phone is as they turn, and reports points it has recognised on the surfaces
 * around them; those accumulate, and when the scan ends they are handed to [PassageScan], which
 * slices them at right angles to the passage and draws the wall.
 *
 * ## What it uses, and the better thing it does not
 *
 * `ARFrame.rawFeaturePoints`, which is the sparse cloud ARKit builds while tracking. It is not the
 * best source available: on a phone with lidar, `sceneReconstruction` gives a dense triangle mesh
 * that would draw a far better wall, and `sceneDepth` gives a depth image better still.
 *
 * Two reasons for the sparse cloud all the same, and the first is the honest one. This was written
 * without a Mac, let alone an iPhone: the mesh arrives as a Metal buffer that has to be walked by
 * hand, and walking a raw buffer wrongly is a crash rather than a bad drawing. Feature points come
 * as a plain C array of positions and a count, which is a thing this file can read correctly
 * without a device to try it on. The second is that the sparse cloud works on *every* ARKit phone
 * rather than only on the Pro models with lidar, and a caver's phone is whatever survived the last
 * trip.
 *
 * `PassageScan` was written for exactly this kind of input — it takes a percentile of each sector
 * rather than the farthest point, needs several points before it believes a direction, and leaves
 * blank what was not scanned — so a sparse noisy cloud degrades into a rougher wall rather than
 * into a wrong one. Moving to the mesh later changes this file and nothing else.
 *
 * ## Status: written on Linux, compiled by CI, never pointed at a cave
 *
 * Like `CoreBluetoothTransport` and the camera beside it, the macOS runner is the first thing to
 * compile this and there is nothing here it can run: the simulator has no ARKit camera, so
 * [available] is false on the one machine that proves this builds. Unverified past that point:
 * whether feature points are dense enough on wet limestone under a head torch to make a wall
 * (bare rock in torchlight is a hard case for a system that wants texture and light), what the
 * tracking does when the surveyor turns on the spot in the dark, and whether the sweep can be done
 * one-handed while holding a light.
 *
 * ## Two things about the frame that matter to a surveyor
 *
 * The scan is centred on the *phone*, not on the station: ARKit's origin is where the session
 * started, which is chest height wherever the surveyor was standing. For a cross-section that is
 * close enough — a station is taken from about there anyway — but a section scanned from the far
 * side of a chamber is a section of the wrong place, and the app cannot tell.
 *
 * And ARKit's north is true north, where a survey bearing off a DistoX is magnetic. The difference
 * is the local declination, a degree or two in Britain and more elsewhere; it turns the slice by
 * that much, which over a slab a quarter of a metre thick is not something a wall notices. It is
 * written down because it is the kind of thing that is invisible until somebody scans a cave in a
 * part of the world where declination is fifteen degrees.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPassageScanner(onScanned: (List<Coord3D>) -> Unit): PassageScanner {
    // The scan comes back a long time after the tap that asked for it — half a minute of sweeping
    // — so this keeps the delivery pointed at the current callback rather than the one that
    // happened to be passed when the session opened. The same reasoning as the camera's.
    val deliver = rememberUpdatedState(onScanned)

    return remember { ArKitScanner { points -> deliver.value(points) } }
}

/**
 * Empty on a phone that can run ARKit, and a sentence everywhere else.
 *
 * The simulator is named because that is where most people will first meet this build, and "the
 * scan button does nothing" is a bug report somebody would otherwise file.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun whyNoScanner(): String =
    if (ARWorldTrackingConfiguration.isSupported()) {
        ""
    } else {
        "This device cannot scan a passage. The iOS Simulator has no camera to track with, so " +
            "scanning only works on a real iPhone or iPad."
    }

@OptIn(ExperimentalForeignApi::class)
private class ArKitScanner(private val onScanned: (List<Coord3D>) -> Unit) : PassageScanner {

    /**
     * Asked of ARKit rather than of the model name.
     *
     * `isSupported` is false on the simulator and on any device too old to track, which is exactly
     * the set that should not be offered a scan button. It says nothing about lidar, deliberately:
     * feature points come from tracking rather than from a depth sensor, so a phone without lidar
     * still scans — worse, but not not at all.
     */
    override val available: Boolean = ARWorldTrackingConfiguration.isSupported()

    override fun scan() {
        if (!available) return
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
        // Presented over whatever is on screen rather than replacing it, so that dismissing the
        // scan puts the surveyor back on the drawing they came from with nothing lost.
        root.presentViewController(ScanViewController(onScanned), animated = true, completion = null)
    }
}

/**
 * The screen the surveyor sees while scanning: the camera, a count, and a button to finish.
 *
 * Hand-built rather than a storyboard, as `MainViewController.kt` is, because this project's iOS
 * half is two Swift files and everything else is Kotlin.
 *
 * The count is the important part of it and not decoration. A scan gathers nothing at all if
 * tracking never starts — a lens against a wall, a room too dark for ARKit to find anything to
 * track — and without a number on the screen the surveyor learns that a minute later, when the
 * cross-section comes back blank, standing somewhere they have to walk back to.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class ScanViewController(
    private val onScanned: (List<Coord3D>) -> Unit,
) : UIViewController(nibName = null, bundle = null) {

    private val gathered = mutableListOf<Coord3D>()
    private var arView: ARSCNView? = null
    private var counter: UILabel? = null
    private var timer: NSTimer? = null
    private var finished = false
    private var finishButton: UIButton? = null

    override fun viewDidLoad() {
        super.viewDidLoad()

        val bounds = UIScreen.mainScreen.bounds
        val view = ARSCNView(frame = bounds)
        view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        this.view.addSubview(view)
        arView = view

        val label = UILabel(frame = CGRectMake(0.0, 60.0, 0.0, 40.0))
        label.textAlignment = NSTextAlignmentCenter
        label.textColor = UIColor.whiteColor
        label.text = SCANNING_NOTHING_YET
        this.view.addSubview(label)
        counter = label

        val done = UIButton.buttonWithType(UIButtonTypeSystem)
        done.setTitle(FINISH_TITLE, forState = UIControlStateNormal)
        done.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        done.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.6)
        done.addTarget(this, sel_registerName("finish"), UIControlEventTouchUpInside)
        this.view.addSubview(done)
        finishButton = done

        layOut(bounds.useContents { size.width }, bounds.useContents { size.height })
    }

    /**
     * Frames worked out rather than constrained.
     *
     * Auto Layout from Kotlin/Native means building `NSLayoutConstraint`s by hand, which is a lot
     * of interop for two controls on a screen that never rotates while it is being used — a
     * surveyor scanning a passage is turning the phone about, and a layout that reflowed underneath
     * them would be worse than one that does not.
     */
    private fun layOut(width: Double, height: Double) {
        counter?.setFrame(CGRectMake(0.0, 60.0, width, 40.0))
        finishButton?.setFrame(CGRectMake(width / 2 - 90, height - 120, 180.0, 56.0))
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)

        val configuration = ARWorldTrackingConfiguration()
        // True north and gravity, so that a bearing means the same thing to the scan as it does to
        // the survey — see the note about declination on this file.
        configuration.worldAlignment = ARWorldAlignmentGravityAndHeading
        arView?.session?.runWithConfiguration(configuration)

        // Read on a timer rather than through a delegate. `ARSessionDelegate.didUpdateFrame` fires
        // sixty times a second, and a scan does not need sixty samples a second of a cloud that
        // changes slowly — it needs a sweep's worth. Five a second over half a minute is a hundred
        // and fifty reads, which is plenty and is a great deal less heat in a cold phone.
        timer = NSTimer.scheduledTimerWithTimeInterval(
            interval = SAMPLE_SECONDS,
            repeats = true,
        ) { _ -> sample() }
    }

    /**
     * One read of what ARKit has recognised so far, converted and kept.
     *
     * Feature points are cumulative and re-reported, so the same piece of rock arrives many times
     * over a sweep. That is left alone rather than deduplicated: `PassageScan` takes a percentile
     * of each sector, and a wall reported ten times is a wall the percentile is more sure of, which
     * is the right way round.
     */
    private fun sample() {
        val frame = arView?.session?.currentFrame ?: return
        val cloud = frame.rawFeaturePoints ?: return
        val count = cloud.count.toInt()
        if (count <= 0) return
        // A `simd_float3` is four floats wide in memory, not three: the fourth is padding the
        // vector unit needs for alignment. Striding by three would read every point after the first
        // out of the middle of its neighbours, and the wall would come out as noise. This is the
        // one line in the file most likely to be wrong and least likely to look it.
        val floats = cloud.points.reinterpret<FloatVar>()

        var index = 0
        while (index < count && gathered.size < SCAN_POINT_LIMIT) {
            val base = index * FLOATS_PER_POINT
            // Already relative to the surveyor: ARKit puts its world origin where the device was
            // when the session started, which is where they were standing when they pressed scan.
            // Nothing here subtracts a camera position, and nothing should — the camera has moved
            // by the time a point is reported, and subtracting where it is now would smear the
            // passage across the sweep.
            //
            // ARKit's axes into the survey's. Aligned to gravity and heading, ARKit gives x east,
            // y up and negative z north; `toCartesian` builds x east, y north, z up.
            gathered.add(Coord3D(floats[base], -floats[base + 2], floats[base + 1]))
            index++
        }

        counter?.text = if (gathered.isEmpty()) SCANNING_NOTHING_YET else scanningCount(gathered.size)

        if (gathered.size >= SCAN_POINT_LIMIT) finish()
    }

    /**
     * Hand the scan over and get off the screen.
     *
     * Called by the button through `sel_registerName`, and by [sample] when the cap is reached, so
     * it has to be safe to call twice — a surveyor pressing the button as the cap is hit is not a
     * race worth losing a scan to. Stopping the timer first is what makes it safe.
     */
    @Suppress("unused")
    @ObjCAction
    fun finish() {
        if (finished) return
        finished = true
        timer?.invalidate()
        timer = null
        arView?.session?.pause()
        val points = gathered.toList()
        dismissViewControllerAnimated(true) { onScanned(points) }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        // Whatever route was taken off this screen. An ARKit session left running holds the camera
        // and the neural engine, which on a phone underground is the battery a surveyor needs to
        // get out on.
        timer?.invalidate()
        timer = null
        arView?.session?.pause()
    }
}

/** Four, not three: see the note in `sample` about what padding does to a misread stride. */
private const val FLOATS_PER_POINT = 4

/** Five reads a second, which is a sweep's worth without cooking the phone. */
private const val SAMPLE_SECONDS = 0.2

/**
 * The wording, typed here rather than mirrored from `strings.xml`.
 *
 * `Strings.local` exists for exactly this case and cannot be used from `iosMain`: this screen is
 * UIKit rather than Compose, so it is built before anything Compose knows about is on screen. The
 * Android app has no scanner and so no resource to be held to, which is what makes typing them out
 * honest rather than lazy — see the same note on `whyNoCamera`.
 */
private const val SCANNING_NOTHING_YET = "Sweep the phone round the passage"

private const val FINISH_TITLE = "Done"

private fun scanningCount(points: Int): String = "$points points"
