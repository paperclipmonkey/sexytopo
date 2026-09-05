package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.sketch.SeenSurfaces
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTimer
import platform.UIKit.NSTextAlignmentCenter
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
 * blank what was not scanned — so a sparse cloud should degrade into a rougher wall rather than
 * into a wrong one. Moving to the mesh later changes this file and nothing else.
 *
 * "Should" is doing work in that sentence, and see the status below for what a phone made of it.
 *
 * ## Status: run on a phone twice, in a room, and not yet in a cave
 *
 * The macOS runner compiles this and can run none of it — the simulator has no ARKit camera, so
 * available is false on the one machine that proves it builds. Everything known past that comes
 * from two runs on a real device in a well-lit room, and both have been worth more than every check
 * here.
 *
 * The first found three faults at once, all of them the cumulative-cloud bug readCurrentFrame
 * describes; all three are fixed and covered by tests. The second found that fixing them had not
 * cured the symptom the surveyor actually minds, which is that the camera picture stops — and that
 * it now stops for good where it used to stutter and come back. The account of why is on
 * readCurrentFrame, along with the reason that first fix should be expected to have made this
 * particular symptom worse rather than better.
 *
 * Unverified, in the order it matters:
 *  - **whether the frames are in fact being handed back.** Forcing a collection is a workaround
 *    against an unstable corner of the Kotlin runtime, not a guarantee, and nothing on any machine
 *    here can watch ARKit's buffer pool. If a third run still freezes, the answer is to move the
 *    sampling into Swift, where a frame is released at the closing brace and none of this is a
 *    question;
 *  - **whether feature points are dense and accurate enough to draw a passage wall at all.** The
 *    room that produced a star of spikes did so because one stray return was counted a hundred and
 *    fifty times, which is fixed — but a scan that freezes after two seconds has never had the
 *    chance to answer this. A phone with lidar carries a depth map and a triangle mesh that would
 *    both be better sources, and the phone in question has one. That is the next change if the wall
 *    still comes back thin, and it is deliberately not this one: moving the sensor and the frame
 *    handling in the same step would leave neither of them answered;
 *  - whether the sweep can be done one-handed while holding a light, and what the tracking does
 *    when a surveyor turns on the spot in the dark.
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
        // The same walk the camera uses, shared rather than copied: it climbs the presented chain,
        // so the scan opens over whatever is already on screen rather than under it. Presented
        // rather than pushed, so that dismissing it puts the surveyor back on the drawing they came
        // from with nothing lost.
        val host = topmostViewController() ?: return
        host.presentViewController(ScanViewController(onScanned), animated = true, completion = null)
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
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, NativeRuntimeApi::class)
private class ScanViewController(
    private val onScanned: (List<Coord3D>) -> Unit,
) : UIViewController(nibName = null, bundle = null) {

    private val gathered = mutableListOf<Coord3D>()
    private var arView: ARSCNView? = null
    private var counter: UILabel? = null
    private var timer: NSTimer? = null
    private var finished = false
    private var finishButton: UIButton? = null

    /**
     * One entry per small box of space already kept, so the cumulative cloud is only news once.
     *
     * A set of packed integers rather than of points: the whole cloud is walked on every read, so
     * this is asked several thousand times a second and wants to cost a hash of a `Long`.
     */
    private val seen = SeenSurfaces()

    /** Reads so far, which is this screen's clock — see the backstop in [sample]. */
    private var samples = 0

    /**
     * When ARKit last produced a frame, and how many reads have gone by without a newer one.
     *
     * The only way this screen can tell a camera that is working and finding nothing from one that
     * has stopped, because on the face of it they are the same thing: a count that does not move.
     * Telling them apart is worth the two fields — a surveyor who knows the camera has died can
     * stop and start again, where one watching a still number waits out the whole half minute.
     */
    private var lastFrameTime = 0.0
    private var stalledReads = 0

    override fun viewDidLoad() {
        super.viewDidLoad()

        // `UIViewController.view` is nullable across the interop boundary even though UIKit
        // guarantees it by the time this is called, so it is taken once rather than dereferenced
        // four times.
        val root = view ?: return
        val bounds = UIScreen.mainScreen.bounds

        val camera = ARSCNView(frame = bounds)
        camera.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        root.addSubview(camera)
        arView = camera

        val label = UILabel(frame = CGRectMake(0.0, 60.0, 0.0, 40.0))
        label.textAlignment = NSTextAlignmentCenter
        label.textColor = UIColor.whiteColor
        label.text = SCANNING_NOTHING_YET
        root.addSubview(label)
        counter = label

        val done = UIButton.buttonWithType(UIButtonTypeSystem)
        done.setTitle(FINISH_TITLE, forState = UIControlStateNormal)
        done.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        done.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.6)
        done.addTarget(this, sel_registerName("finish"), UIControlEventTouchUpInside)
        root.addSubview(done)
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
        configuration.worldAlignment = GRAVITY_AND_HEADING
        arView?.session?.runWithConfiguration(configuration)

        // Read on a timer rather than through a delegate. `ARSessionDelegate.didUpdateFrame` fires
        // sixty times a second, and a scan does not need sixty samples a second of a cloud that
        // changes slowly — it needs a sweep's worth. Two a second over half a minute is sixty
        // reads, which is plenty, is a great deal less heat in a cold phone, and is sixty frames to
        // hand back rather than eighteen hundred.
        timer = NSTimer.scheduledTimerWithTimeInterval(
            interval = SAMPLE_SECONDS,
            repeats = true,
        ) { _ -> sample() }
    }

    /**
     * One read: whatever ARKit has recognised that has not been kept already, and then the frame
     * handed back before the next tick.
     *
     * The two halves are split across this method and readCurrentFrame, and the split is load
     * bearing rather than tidiness — see that method for why the collection has to be out here.
     *
     * ## What is being counted, and what a still count means
     *
     * `rawFeaturePoints` is *cumulative*: every read hands back the entire cloud ARKit is holding,
     * not the part that is new since the last one. Appending all of it, several times a second,
     * appends the same rock over and over, and a phone standing still on a table gathered points as
     * fast as one being swept round a chamber.
     *
     * Reported from a device, and it was three faults rather than one, all from that:
     *
     *  - the count climbed without the phone moving, because it was counting reads rather than rock;
     *  - the cap of [SCAN_POINT_LIMIT] was reached in seconds, so a scan meant to last half a minute
     *    ended having seen one wall from one angle;
     *  - and the cross-section came out as a star of spikes, which is the subtle one. `PassageScan`
     *    rejects a direction holding fewer than three points, and takes a sector's eightieth
     *    percentile rather than its farthest point. Both defences count *observations*. A single
     *    stray return duplicated a hundred and fifty times clears the three-point bar on its own
     *    and is a hundred and fifty of the hundred and fifty values the percentile sorts, so it
     *    becomes a confident wall. Duplication did not merely fail to help the percentile, as the
     *    comment here used to claim — it disabled the noise floor entirely.
     *
     * The frozen picture was blamed on this too, and that was wrong: it was the same read doing the
     * damage, but through the frame it was borrowing rather than the points it was copying. The
     * correction is on readCurrentFrame, where it belongs.
     *
     * So the same surface is kept once, through [SeenSurfaces]. That lives in the shared module
     * rather than here because it is arithmetic and because its bit-packing fails *silently* — a
     * scan would simply come out sparse — so it wants to be somewhere a test can run. Its own
     * documentation says why space is quantised rather than the scanner's per-point identifiers
     * trusted.
     *
     * A consequence of that fix is that a count which stops moving is now the *ordinary* thing to
     * see: a surveyor holding still is finding no new rock, and should not be told anything is
     * wrong. Which is exactly why a stopped camera needs saying out loud rather than being left to
     * look like the same thing. The frame's own clock is what separates them.
     */
    private fun sample() {
        samples++
        // The backstop this file documented and never had: a surveyor who forgets about a running
        // scan is standing in the dark holding a camera. Counted in ticks rather than clock-read,
        // since the tick is the only clock this screen needs.
        if (samples * SAMPLE_SECONDS >= SCAN_SECONDS) {
            finish()
            return
        }

        val frameTime = readCurrentFrame()

        // Hand ARKit its frame back, and it has to be here rather than in there: see the note on
        // readCurrentFrame about which stack frames a collection can and cannot reach.
        GC.collect()

        if (frameTime == null) return

        if (frameTime > lastFrameTime) {
            lastFrameTime = frameTime
            stalledReads = 0
        } else {
            stalledReads++
        }

        counter?.text =
            when {
                stalledReads * SAMPLE_SECONDS >= STALL_SECONDS -> CAMERA_STOPPED
                gathered.isEmpty() -> SCANNING_NOTHING_YET
                else -> scanningCount(gathered.size)
            }

        if (gathered.size >= SCAN_POINT_LIMIT) finish()
    }

    /**
     * Take the points out of ARKit's current frame, and get out of its way.
     *
     * ## Why this is a method of its own, and why the collection is not in it
     *
     * ARKit draws from a small fixed pool of frame buffers, and Apple's instruction about
     * `currentFrame` is not to hold one for any longer than it takes to read: a session whose pool
     * is full of frames somebody else is still holding cannot produce another one. What a surveyor
     * sees when that happens is the camera picture stopping dead.
     *
     * Kotlin makes that easy to do without meaning to. An Objective-C object reached from Kotlin is
     * released when the garbage collector gets round to the wrapper holding it, not when the
     * variable goes out of scope — so reading `currentFrame` on a timer quietly stockpiles ARFrames
     * until a collection happens to run, and nothing in an app like this one asks for a collection.
     *
     * That is the best account there is of what a phone reported: a scan that runs for a couple of
     * seconds — about ten reads at the old rate, which is about the size of the pool — and then
     * freezes with the count stuck. It also explains the shape of the report *before* it, where the
     * picture froze for a few seconds at a time and then came back. The version that kept every
     * point of the cumulative cloud on every read was allocating hard enough to trigger collections
     * by itself, and each one handed the hoard of frames back and let the session breathe. Removing
     * the duplication removed that accident, and turned a stutter into a stop. A fix making a
     * symptom worse is worth writing down: it is the thing that pointed here.
     *
     * So the frame is touched in this method and nowhere else, and [sample] forces a collection the
     * instant it returns. The split is the point. A collection asked for from in here could free
     * nothing, because the frame would still be a live local of the very function asking — it has
     * to be a stack frame that has already been popped.
     *
     * Hands back the frame's timestamp, which is how the caller tells a stopped camera from a quiet
     * one, or null when there is no frame yet.
     */
    private fun readCurrentFrame(): Double? {
        val frame = arView?.session?.currentFrame ?: return null

        val cloud = frame.rawFeaturePoints
        val count = cloud?.count?.toInt() ?: 0
        // A `simd_float3` is four floats wide in memory, not three: the fourth is padding the
        // vector unit needs for alignment. Striding by three would read every point after the first
        // out of the middle of its neighbours, and the wall would come out as noise. This is the
        // one line in the file most likely to be wrong and least likely to look it.
        val floats = cloud?.points?.reinterpret<FloatVar>()

        if (floats != null) {
            var index = 0
            while (index < count && gathered.size < SCAN_POINT_LIMIT) {
                val base = index * FLOATS_PER_POINT
                // Already relative to the surveyor: ARKit puts its world origin where the device
                // was when the session started, which is where they were standing when they pressed
                // scan. Nothing here subtracts a camera position, and nothing should — the camera
                // has moved by the time a point is reported, and subtracting where it is now would
                // smear the passage across the sweep.
                //
                // ARKit's axes into the survey's. Aligned to gravity and heading, ARKit gives x
                // east, y up and negative z north; `toCartesian` builds x east, y north, z up.
                val east = floats[base]
                val north = -floats[base + 2]
                val up = floats[base + 1]
                index++
                // Seen before, in this box of space, on any earlier read: not news.
                if (!seen.isNew(east, north, up)) continue
                gathered.add(Coord3D(east, north, up))
            }
        }

        return frame.timestamp
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

/**
 * Gravity and true north, named once because this is the spelling that was wrong first time.
 *
 * Kotlin/Native exposes some Objective-C `NS_ENUM`s as enum classes and others as bare top-level
 * constants, and which one you get is not something a Linux machine can find out — this was
 * written as the bare constant, and the macOS runner rejected it. `ARWorldAlignment` is the first
 * kind, as `UIImagePickerControllerSourceType` next door is; `CBManagerStatePoweredOn` in
 * `CoreBluetoothTransport` is the second.
 */
private val GRAVITY_AND_HEADING = ARWorldAlignment.ARWorldAlignmentGravityAndHeading

/** Four, not three: see the note in `readCurrentFrame` about padding and a misread stride. */
private const val FLOATS_PER_POINT = 4

/**
 * Two reads a second, which is a sweep's worth without cooking the phone.
 *
 * It was five, and the reduction is not about processor time. Every read borrows a frame from
 * ARKit's pool and has to give it back, so the rate is also the rate at which this screen leans on
 * a mechanism that is documented on `readCurrentFrame` and is not guaranteed. Two a second is still
 * far more often than a passage changes shape, and asks two and a half times less of it.
 */
private const val SAMPLE_SECONDS = 0.5

/**
 * How long the picture has to be still before the surveyor is told it has stopped.
 *
 * Long enough not to cry wolf over a dropped frame or two, short enough to be seen and acted on
 * while they are still standing in the right place.
 */
private const val STALL_SECONDS = 2.0

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

/**
 * Said when ARKit has stopped producing frames, which a stuck count on its own does not mean.
 *
 * A surveyor holding still finds no new rock and the count stops, and that is the scan working. The
 * difference is invisible from the outside, and the wrong reading of it costs a trip: half a minute
 * of sweeping a dead camera, or an abandoned scan that was only quiet.
 */
private const val CAMERA_STOPPED = "The camera has stopped - tap Done and scan again"

private fun scanningCount(points: Int): String = "$points points"
