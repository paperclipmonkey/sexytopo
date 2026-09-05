package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CStructVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ptr
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.sketch.DepthCamera
import org.hwyl.sexytopo.shared.sketch.SeenSurfaces
import platform.ARKit.ARFrame
import platform.ARKit.ARFrameSemanticNone
import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARFrameSemanticSmoothedSceneDepth
import platform.ARKit.ARFrameSemantics
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGRectMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
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
import kotlin.math.roundToInt

/**
 * Scanning the shape of a passage with ARKit.
 *
 * The surveyor stands at the station, opens this, and sweeps the phone round the passage. ARKit
 * tracks where the phone is as they turn, and reports points it has recognised on the surfaces
 * around them; those accumulate, and when the scan ends they are handed to [PassageScan], which
 * slices them at right angles to the passage and draws the wall.
 *
 * ## What it reads, and what it falls back on
 *
 * The lidar, where there is one: `ARFrame.sceneDepth`, smoothed. That is a small picture — 256 by
 * 192 on the phones that have it — in which every pixel is a *measured* distance to whatever that
 * pixel is looking at. Dense, and indifferent to whether the rock has any texture on it, which is
 * the whole difficulty with the alternative underground.
 *
 * The alternative being `ARFrame.rawFeaturePoints`, the sparse cloud ARKit builds while tracking,
 * which is what every phone without lidar gets and what this file used exclusively to begin with.
 * The reasoning then was that it works on every ARKit phone and that a plain C array of positions
 * is something a file written on Linux can read correctly, where a depth buffer has to be locked,
 * walked and un-projected through the camera's own optics — and walking a buffer wrongly is a
 * crash rather than a bad drawing.
 *
 * The first half of that still holds and is why the feature-point path is kept rather than
 * deleted: a caver's phone is whatever survived the last trip, and most of them are not Pro
 * models. The second half was answered by putting the un-projection where it could be tested.
 * `DepthCamera` in the shared module is pure arithmetic held to known answers by
 * `DepthCameraTest` — a camera in a known place looking a known way, and where a given pixel's
 * rock ought to come out. That covers the failure worth fearing, which is not a crash but a
 * mirrored or upside-down passage: a scan that looks like it nearly worked and cannot be told
 * from a good one without knowing the answer in advance.
 *
 * What remains unproven there is the convention rather than the arithmetic — that ARKit means by
 * a pose, a set of optics and a depth what `DepthCamera` says it does. Its own documentation lists
 * each assumption and what a wrong one would look like on the drawing.
 *
 * ## Status: run on a phone three times, in a room, and not yet in a cave
 *
 * The macOS runner compiles this and can run none of it — the simulator has no ARKit camera, so
 * available is false on the one machine that proves it builds. Everything known past that comes
 * from three runs on a real device in a well-lit room, and all three have been worth more than
 * every check here.
 *
 * The first found three faults at once, all of them the cumulative-cloud bug
 * `gatherFromFeaturePoints` describes; all three are fixed and covered by tests. The second found
 * that fixing them had not cured the symptom the surveyor actually minds, which is the camera
 * picture stopping — and that it now stopped for good where it used to stutter and recover.
 * `readCurrentFrame` sets out what that turned out to be, and why the first fix should have been
 * expected to make it worse. **The third run says the picture no longer stops**, which is the one
 * piece of good news in the sequence and closes that question.
 *
 * It also says the wall is still junk, which is what moved this to the depth map: a scan running
 * its full half minute and still drawing nonsense is the sparse cloud being answered on its
 * merits, rather than a symptom of something else.
 *
 * Unverified, in the order it matters:
 *  - **whether the depth map draws a passage that is actually there.** A wrong convention would
 *    show as a mirrored, upside-down or quarter-turned section, and a depth read as a ray length
 *    rather than along the lens axis would show as a passage a little too wide, bulging where each
 *    frame's edges fell. Comparing the drawn wall against the splays already on the section is what
 *    tells those apart, and it wants a station where the splays are known;
 *  - **whether it holds up in a cave.** Lidar does not care about darkness, which is the one thing
 *    that ought to be better underground than in a room. It does care about wet, black, and far
 *    away — all three at once is the normal condition of a passage wall, and a phone's lidar is
 *    rated to about five metres in ideal conditions;
 *  - whether the sweep can be done one-handed while holding a light, and what the tracking does
 *    when a surveyor turns on the spot in the dark. Tracking is still what places every point,
 *    lidar or not, so a room with nothing to track in it is still the hard case.
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
     * Which sensor this scan is reading, decided once when the screen is built.
     *
     * Once, and not per frame, because the two sources want different treatment all the way down —
     * a different voxel, a different count on the screen — and because a scan that silently
     * switched halfway would be two scans of different quality glued together.
     */
    private val usesDepth = DEPTH_SEMANTIC != ARFrameSemanticNone

    /**
     * One entry per small box of space already kept, so the same rock is only news once.
     *
     * A set of packed integers rather than of points: a depth picture is fifty thousand pixels and
     * the whole of it is walked on every read, so this is asked tens of thousands of times a second
     * and wants to cost a hash of a `Long`.
     *
     * Coarser for lidar than for tracking, and that is not a detail. Feature points are scarce
     * enough that two centimetres throws almost nothing away; a depth picture measures every
     * pixel, so two-centimetre boxes would fill the cap with one chamber's worth of wall and end
     * the scan early. Five centimetres is finer than any wall a surveyor draws by hand.
     */
    private val seen =
        SeenSurfaces(
            if (usesDepth) DEPTH_VOXEL_METRES else SeenSurfaces.DEFAULT_VOXEL_METRES,
        )

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
        // Asked for only where it is supported, which is what keeps a scan working on a phone
        // without lidar rather than failing to start on one. ARKit refuses a configuration
        // carrying a semantic the device cannot do, so this is a guard rather than a preference.
        if (usesDepth) configuration.frameSemantics = DEPTH_SEMANTIC
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
     * One read: whatever the sensor has that has not been kept already, and then the frame handed
     * back before the next tick.
     *
     * The two halves are split across this method and readCurrentFrame, and the split is load
     * bearing rather than tidiness — see that method for why the collection has to be out here.
     *
     * ## Why a count that stops moving is not a fault
     *
     * Both sources are asked for everything they have on every read rather than for what is new,
     * so what makes the count go up is not the reading but the surveyor: rock this scan has not
     * seen before. [SeenSurfaces] is what draws that line, and a surveyor holding the phone still
     * is finding no new rock and ought to see the count sit exactly where it is.
     *
     * That is the ordinary case, and it is indistinguishable from the camera having died. Which is
     * why a dead one has to say so out loud, and why the frame's own clock rather than the count is
     * what this watches: a still count with a moving clock is a surveyor standing still, and a
     * still clock is ARKit having stopped.
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
                else -> scanningCount(gathered.size, usesDepth)
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
        if (usesDepth) gatherFromDepth(frame) else gatherFromFeaturePoints(frame)
        return frame.timestamp
    }

    /**
     * The lidar's depth picture, un-projected into points on the rock.
     *
     * Every pixel of it is a measured distance to whatever that pixel is looking at, which is a
     * different order of thing from the tracking cloud: dense, and indifferent to whether the rock
     * has any texture to recognise. It is the reason a Pro phone can measure a passage the sparse
     * cloud could only sketch at.
     *
     * The arithmetic that turns a distance at a pixel into a place in the cave is not here — it is
     * `DepthCamera` in the shared module, where tests can hold it to a known answer, because
     * getting a sign wrong there draws a mirrored or upside-down passage rather than nothing at
     * all. What is here is the part only a phone has: locking the buffer, walking it, and throwing
     * away the readings not worth keeping.
     *
     * Three filters, and each one earns its place:
     *
     *  - **confidence.** ARKit grades every pixel low, medium or high, and low is where a
     *    reflection off wet rock or a puddle ends up. Those are exactly the returns that used to
     *    become confident walls, so the low ones are dropped outright.
     *  - **range.** Lidar on a phone is good to about five metres and says something anyway past
     *    that. Beyond the far limit a reading is noise wearing the shape of a measurement; nearer
     *    than a quarter of a metre it is usually the surveyor's own hand.
     *  - **not a number.** A pixel that saw nothing comes back as zero or worse, and a NaN
     *    propagates all the way into the sketch as a stroke going nowhere.
     */
    private fun gatherFromDepth(frame: ARFrame) {
        val depth = frame.smoothedSceneDepth ?: frame.sceneDepth ?: return
        val map = depth.depthMap
        val confidence = depth.confidenceMap

        val camera = frame.camera
        val (imageWidth, imageHeight) =
            camera.imageResolution.useContents { width.roundToInt() to height.roundToInt() }
        if (imageWidth <= 0 || imageHeight <= 0) return

        CVPixelBufferLockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
        if (confidence != null) {
            CVPixelBufferLockBaseAddress(confidence, kCVPixelBufferLock_ReadOnly)
        }
        try {
            val width = CVPixelBufferGetWidth(map).toInt()
            val height = CVPixelBufferGetHeight(map).toInt()
            if (width <= 0 || height <= 0) return
            val depthBytes = CVPixelBufferGetBaseAddress(map)?.reinterpret<ByteVar>() ?: return
            val depthRowBytes = CVPixelBufferGetBytesPerRow(map).toInt()

            val gradeBytes = confidence?.let {
                CVPixelBufferGetBaseAddress(it)?.reinterpret<ByteVar>()
            }
            val gradeRowBytes = confidence?.let { CVPixelBufferGetBytesPerRow(it).toInt() } ?: 0
            // Only usable if it is the same shape as the depth picture, which it always is and
            // which nothing here would notice if it stopped being.
            val grades =
                if (gradeBytes != null &&
                    CVPixelBufferGetWidth(confidence).toInt() == width &&
                    CVPixelBufferGetHeight(confidence).toInt() == height
                ) {
                    gradeBytes
                } else {
                    null
                }

            val lens =
                DepthCamera.forDepthImage(
                    intrinsics = camera.intrinsics.floatsOfStruct(DepthCamera.INTRINSICS_FLOATS),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    depthWidth = width,
                    depthHeight = height,
                    transform = camera.transform.floatsOfStruct(DepthCamera.TRANSFORM_FLOATS),
                )

            var row = 0
            while (row < height && gathered.size < SCAN_POINT_LIMIT) {
                val depths = (depthBytes + row.toLong() * depthRowBytes)!!.reinterpret<FloatVar>()
                val rowGrades =
                    grades?.let { (it + row.toLong() * gradeRowBytes)!!.reinterpret<UByteVar>() }
                var column = 0
                while (column < width && gathered.size < SCAN_POINT_LIMIT) {
                    val metres = depths[column]
                    val graded = rowGrades == null || rowGrades[column] > LOW_CONFIDENCE
                    if (graded &&
                        metres.isFinite() &&
                        metres >= NEAREST_USEFUL_METRES &&
                        metres <= FURTHEST_USEFUL_METRES
                    ) {
                        val point = lens.pointAt(column, row, metres)
                        if (seen.isNew(point.x, point.y, point.z)) gathered.add(point)
                    }
                    column += DEPTH_PIXEL_STEP
                }
                row += DEPTH_PIXEL_STEP
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
            if (confidence != null) {
                CVPixelBufferUnlockBaseAddress(confidence, kCVPixelBufferLock_ReadOnly)
            }
        }
    }

    /**
     * The sparse cloud ARKit builds while tracking, for the phones with no lidar to ask instead.
     *
     * Kept rather than dropped once the depth path existed, because a caver's phone is whatever
     * survived the last trip and most of them are not Pro models. It is the worse source by a wide
     * margin — inferred from texture rather than measured, thin on bare wet rock, and the reason
     * this feature spent its first two device runs producing spikes — but a rough wall a surveyor
     * can draw over beats a button that is not offered.
     *
     * ## The cumulative cloud, and what it did
     *
     * `rawFeaturePoints` is *cumulative*: every read hands back the entire cloud ARKit is holding,
     * not the part that is new since the last one. Appending all of it, several times a second,
     * appends the same rock over and over, and a phone standing still on a table gathered points
     * as fast as one being swept round a chamber.
     *
     * Reported from a device, and it was three faults rather than one, all from that:
     *
     *  - the count climbed without the phone moving, because it was counting reads not rock;
     *  - the cap of [SCAN_POINT_LIMIT] was reached in seconds, so a scan meant to last half a
     *    minute ended having seen one wall from one angle;
     *  - and the cross-section came out as a star of spikes, which is the subtle one. `PassageScan`
     *    rejects a direction holding fewer than three points, and takes a sector's eightieth
     *    percentile rather than its farthest point. Both defences count *observations*. A single
     *    stray return duplicated a hundred and fifty times clears the three-point bar on its own
     *    and is a hundred and fifty of the hundred and fifty values the percentile sorts, so it
     *    becomes a confident wall. Duplication did not merely fail to help the percentile, as the
     *    comment here used to claim — it disabled the noise floor entirely.
     *
     * The frozen picture was blamed on this too, and that was wrong: it was the same read doing
     * the damage, but through the frame it was borrowing rather than the points it was copying.
     * The correction is on readCurrentFrame, where it belongs.
     *
     * So the same surface is kept once, through [SeenSurfaces]. That lives in the shared module
     * rather than here because it is arithmetic and because its bit-packing fails *silently* — a
     * scan would simply come out sparse — so it wants to be somewhere a test can run. The depth
     * path leans on exactly the same thing, for a different reason: fifty thousand pixels a read,
     * most of them looking at rock the last read already measured.
     */
    private fun gatherFromFeaturePoints(frame: ARFrame) {
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

/** Four, not three: see the note in `gatherFromFeaturePoints` about padding and a stride. */
private const val FLOATS_PER_POINT = 4

/**
 * A simd matrix read as the floats it is, rather than through whatever cinterop called its fields.
 *
 * ARKit reports a camera's pose and its optics as simd matrices, and how those come across the
 * interop boundary is not something a Linux machine can find out — which of `columns`, an array or
 * a set of named members it turns into varies, and getting it wrong is a compile error at best and
 * a silently transposed matrix at worst. What does not vary is the memory: a matrix of floats is
 * floats, laid out column by column, exactly as `ARPointCloud.points` is points. So this reads it
 * the same way the point cloud is read, which is the one technique in this file already proven on
 * a phone.
 *
 * The count is the caller's to state, and `DepthCamera` states it: sixteen for a four-by-four
 * pose, twelve — not nine — for the three-by-three of optics, because a three-wide column occupies
 * four floats. Reading past the end of a struct is not an exception, it is whatever was next in
 * memory, which is why the far end asserts the length it was promised.
 */
@OptIn(ExperimentalForeignApi::class)
private fun <T : CStructVar> CValue<T>.floatsOfStruct(count: Int): FloatArray =
    useContents {
        val floats = ptr.reinterpret<FloatVar>()
        FloatArray(count) { floats[it] }
    }

/**
 * The depth semantic this device can actually do, or none at all.
 *
 * Smoothed in preference to raw. ARKit's smoothed depth is averaged over recent frames, which for
 * a surveyor sweeping slowly round a passage is the right trade every time: a cave is not going
 * anywhere, and the flicker the smoothing removes is precisely the per-frame noise that a
 * percentile has to work around later.
 *
 * None on any phone without lidar, which is most of them, and the scanner falls back to the
 * tracking cloud there rather than refusing to open.
 */
private val DEPTH_SEMANTIC: ARFrameSemantics =
    when {
        ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSmoothedSceneDepth) ->
            ARFrameSemanticSmoothedSceneDepth
        ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth) ->
            ARFrameSemanticSceneDepth
        else -> ARFrameSemanticNone
    }

/**
 * Every second pixel of the depth picture, each way.
 *
 * A quarter of fifty thousand pixels is twelve thousand a read, which at two reads a second is
 * still a point every couple of centimetres on a wall three metres off — far finer than anything
 * that survives being reduced to sixty sectors. The three quarters not read cost nothing and save
 * the main thread work it would only throw away.
 */
private const val DEPTH_PIXEL_STEP = 2

/** Five centimetres: see the note on `seen` for why lidar wants a coarser box than tracking. */
private const val DEPTH_VOXEL_METRES = 0.05f

/** Nearer than this is the surveyor's own hand, or the phone's own case. */
private const val NEAREST_USEFUL_METRES = 0.25f

/**
 * Further than this, a phone's lidar is guessing.
 *
 * It reports something regardless, and what it reports past its range is noise in the shape of a
 * measurement — which is exactly the input that turns into a confident wall four metres away from
 * where the wall is. A big chamber therefore comes back as a gap rather than as a wrong answer,
 * which is the same choice `PassageScan` makes about a direction nobody scanned.
 */
private const val FURTHEST_USEFUL_METRES = 5f

/**
 * ARKit grades a depth pixel low, medium or high; low is where wet rock and puddles land.
 *
 * Not a `const`, since an unsigned byte is an inline class over a primitive rather than a
 * primitive, and which of those the compiler will accept in a compile-time constant is not
 * something worth finding out from a macOS runner.
 */
private val LOW_CONFIDENCE: UByte = 0u

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

/**
 * The count, and which sensor found them.
 *
 * The sensor is named on the screen because it is the one thing about a scan that cannot be worked
 * out afterwards from what it drew. A thin wall from a phone with no lidar is the sparse cloud
 * doing its best; the same thin wall from one with lidar is a bug. Four characters on screen turn
 * a bug report into a useful one.
 */
private fun scanningCount(points: Int, lidar: Boolean): String =
    "$points points (" + (if (lidar) "lidar" else "tracking") + ")"
