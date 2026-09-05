package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CStructVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.sketch.DepthCamera
import org.hwyl.sexytopo.shared.sketch.PassageScan
import org.hwyl.sexytopo.shared.sketch.ScanPreview
import org.hwyl.sexytopo.shared.sketch.SeenSurfaces
import platform.ARKit.ARFrame
import platform.ARKit.ARFrameSemanticNone
import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARFrameSemanticSmoothedSceneDepth
import platform.ARKit.ARFrameSemantics
import platform.ARKit.ARSCNView
import platform.ARKit.ARWorldAlignment
import platform.ARKit.ARWorldTrackingConfiguration
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreLocation.CLLocationManager
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
import platform.QuartzCore.CAShapeLayer
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIApplication
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIView
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
 * The scan is centred on the *phone*, not on the station. ARKit puts its world origin wherever the
 * device was at the instant the session started — which is to say the moment this screen appeared,
 * at chest height, wherever the surveyor happened to be standing. The drawing then treats that
 * point as the station, because `CrossSectionEditor` hands the outline straight into a sketch whose
 * own origin is the station.
 *
 * This used to say that was close enough, since a station is taken from about there anyway. A phone
 * says otherwise: a section scanned from two metres away is a good section of the passage drawn two
 * metres out of place, sitting beside the splays instead of among them, and nothing in the app can
 * tell that from a passage that really is over there.
 *
 * Which makes standing at the station part of the method rather than a nicety, so the screen now
 * says so before the sweep starts. The proper fix is to stop assuming: either let the surveyor
 * shift a finished scan onto the station — there is no tool that moves a drawn stroke today, which
 * is why the assumption has nowhere to fail gracefully — or let them point the phone at the station
 * and tap it before sweeping, and raycast to get the offset. The second is the better answer and
 * the more interop.
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
    // The scan comes back a long time after the tap that asked for it — as long as the surveyor
    // sweeps for — so this keeps the delivery pointed at the current callback rather than the one
    // that happened to be passed when the session opened. The same reasoning as the camera's.
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

    /**
     * Location, asked for because ARKit's north depends on it and nothing else here does.
     *
     * The world a scan is measured in is aligned to gravity *and heading*, which is what makes a
     * bearing mean the same thing to the scan as to the survey. Heading itself is the magnetometer
     * and wants no permission — `DeviceHeading.ios.kt` says so and is right — but ARKit reckons
     * its north as *true* north, and what turns magnetic into true is the local declination, which
     * is a fact about where you are standing. What ARKit does when it cannot work that out is not
     * documented: either it aligns to magnetic north, which is what a survey wants anyway, or it
     * aligns to however the phone happened to be pointing when the session started, which makes
     * every section square, plausible and turned by an unknown angle. Asking is the half of that
     * this file can settle; the bearing on the screen is how the other half gets settled, by
     * somebody standing in a passage with a compass.
     *
     * Asked before the screen opens rather than from inside it, so that the prompt is answered on
     * the drawing the surveyor came from and the session that follows has its answer. Held in a
     * field because a manager collected while its prompt is up takes the prompt down with it.
     */
    private val location = CLLocationManager()

    override fun scan(bearing: Float) {
        if (!available) return
        location.requestWhenInUseAuthorization()
        // The same walk the camera uses, shared rather than copied: it climbs the presented chain,
        // so the scan opens over whatever is already on screen rather than under it. Presented
        // rather than pushed, so that dismissing it puts the surveyor back on the drawing they came
        // from with nothing lost.
        val host = topmostViewController() ?: return
        val screen = ScanViewController(bearing, onScanned)
        // Full screen rather than the card iOS puts up by default, and not for the looks of it: a
        // card is dismissed by dragging it down from anywhere on its face, and a surveyor sweeping
        // a phone one-handed in the dark would sooner or later wipe away a scan they had spent two
        // minutes on. Cancel is a button, for the times it is meant.
        screen.modalPresentationStyle = FULL_SCREEN
        host.presentViewController(screen, animated = true, completion = null)
    }
}

/**
 * The screen the surveyor sees while scanning: the camera, what has been measured, what to do,
 * and the two ways off it.
 *
 * Hand-built rather than a storyboard, as `MainViewController.kt` is, because this project's iOS
 * half is two Swift files and everything else is Kotlin.
 *
 * The count is the important part of it and not decoration. A scan gathers nothing at all if
 * tracking never starts — a lens against a wall, a room too dark for ARKit to find anything to
 * track — and without a number on the screen the surveyor learns that a minute later, when the
 * cross-section comes back blank, standing somewhere they have to walk back to.
 *
 * Under the count are two bearings — the one the phone believes it is pointing on, and the one the
 * passage runs on — and they are not decoration either: together they are the one thing on this
 * screen a surveyor can *check*. Everything a scan measures is placed relative to ARKit's idea of
 * north, and a section measured against a north out by a quarter turn is a good section of the
 * wrong plane, which looks exactly like a good section. Point the phone along the passage and the
 * two numbers should agree; that settles it in five seconds and wants nothing but the screen.
 * `DepthCamera.bearingOf` works the first of them out of the pose ARKit is handing over anyway.
 *
 * Under those, in words rather than in numbers, is what to do with all this: start at the station
 * — the section is drawn from where the phone was when this opened, which is a limitation stated
 * as an instruction — then sweep slowly over everything including the roof and the floor, watch the
 * outline in the corner fill in, and tap Done. None of that is ever learnt by repetition, because a scan is opened
 * once a trip at most; and the manual cannot carry it either, since that file is shared with an
 * Android app which has no scanner in it. So it is said here. It comes down to a single line once
 * the section is filling and there has been time to read it — both, since a phone opened facing a
 * wall measures a direction within a second or two, and an explanation that went that quickly
 * would be one nobody had ever read.
 *
 * **Nothing but the button ends a scan.** It used to stop itself after half a minute, until a
 * surveyor pointed out that a passage takes as long as it takes and that a cut-off firing mid-sweep
 * throws the sweep away. What that costs is a forgotten scan holding the camera and the lidar until
 * somebody notices, which is a real cost underground — so the screen says what stops it, rather
 * than stopping itself and hoping the surveyor had finished.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, NativeRuntimeApi::class)
private class ScanViewController(
    private val bearing: Float,
    private val onScanned: (List<Coord3D>) -> Unit,
) : UIViewController(nibName = null, bundle = null) {

    private val gathered = mutableListOf<Coord3D>()
    private var arView: ARSCNView? = null
    private var topPanel: UIView? = null
    private var counter: UILabel? = null
    private var instructions: UILabel? = null
    private var previewPanel: UIView? = null
    private var timer: NSTimer? = null
    private var finished = false
    private var finishButton: UIButton? = null
    private var cancelButton: UIButton? = null

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

    /**
     * The explanation, with the sentence belonging to whichever sensor this phone is reading.
     *
     * Both limits are the sort of thing that otherwise gets found out as a bug. A lidar phone does
     * not measure the far wall of a big chamber at all, so a surveyor who has not been told about
     * the five metres sweeps at a hole in the section over and over trying to fill it; a phone
     * without lidar wants texture and light on the rock, which is a different technique rather
     * than the same one done worse.
     */
    private val howToScan =
        HOW_TO_SCAN + " " + (if (usesDepth) LIDAR_RANGE else TRACKING_NEEDS_LIGHT)

    /**
     * Reads so far, which is what paces the drawing of the section — see [READS_PER_PREVIEW].
     *
     * It was this screen's clock as well, for the half-minute cut-off that is gone: a scan takes as
     * long as the passage takes, and one that stopped itself mid-sweep threw the sweep away.
     */
    private var samples = 0

    /**
     * The section as it stands, drawn small in the corner while the surveyor sweeps.
     *
     * The one thing on this screen that answers "which way do I still have to point it?". A count
     * of points does not: it rises at the same rate for a surveyor sweeping one wall over and over
     * as for one who has been the whole way round. The drawn section does, because the gaps in it
     * *are* the directions nothing has been measured in — `PassageScan` breaks its strokes exactly
     * there, so the picture and the answer are the same object.
     *
     * A shape layer rather than a `UIView` that draws itself: the path is replaced once a second
     * and nothing else about the panel changes, which is what a `CAShapeLayer` is for.
     */
    private var preview: CAShapeLayer? = null
    private var stationMark: CAShapeLayer? = null

    /**
     * How many of the section's sixty directions have been measured, and a tap when that goes up.
     *
     * The vibration is the half that works when the phone is not being looked at, which underground
     * is most of the time: a surveyor sweeping a wall in the dark feels the section filling in
     * rather than having to hold the screen where they can see it. It fires on a new *direction*
     * rather than on new points, so standing still and gathering the same wall again is silent —
     * which is the information.
     */
    private val haptics = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private var directionsMeasured = 0

    /**
     * Which way the phone is pointing, or null when it is aimed too near straight up or down.
     *
     * Read off the frame's own pose in [readCurrentFrame], which is the only place a frame may be
     * touched, and put on the screen under the count.
     */
    private var pointingAt: Float? = null

    /**
     * How many points the section on the screen was reduced from.
     *
     * Reducing the cloud is a pass over every point gathered so far, and a surveyor who is holding
     * still — reading the screen, working out where to point next, or standing in a chamber whose
     * far wall is out of range — is gathering nothing new for it to reduce. Twice a second, over a
     * scan with no time limit on it, that is a great deal of arithmetic and a great deal of heat
     * for a picture that cannot have changed.
     */
    private var drawnFrom = -1

    /**
     * When ARKit last produced a frame, and how many reads have gone by without a newer one.
     *
     * The only way this screen can tell a camera that is working and finding nothing from one that
     * has stopped, because on the face of it they are the same thing: a count that does not move.
     * Telling them apart is worth the two fields — a surveyor who knows the camera has died can
     * stop and start again, where one watching a still number sweeps a dead lens for as long as
     * their patience lasts, there being nothing else now to end the scan.
     */
    private var lastFrameTime = 0.0
    private var stalledReads = 0

    override fun viewDidLoad() {
        super.viewDidLoad()

        // `UIViewController.view` is nullable across the interop boundary even though UIKit
        // guarantees it by the time this is called, so it is taken once rather than dereferenced
        // four times.
        val root = view ?: return

        // The size of what it sits in, and kept that way by its own autoresizing mask rather
        // than by [layOut]. The screen's bounds are not the same thing: what this sits in is the
        // presented controller's view, and the two agree only while nothing has been turned round.
        val camera = ARSCNView(frame = root.bounds)
        camera.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        root.addSubview(camera)
        arView = camera

        // The status and the instructions inside one dim panel rather than as white text laid
        // straight over the camera. What the camera is looking at is wet rock in a headtorch beam,
        // which is as likely to come out white as black, and a line of white text that cannot be
        // read against it is worse than no line at all.
        val header = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        header.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(PANEL_DIMNESS)
        header.layer.cornerRadius = PANEL_CORNER
        root.addSubview(header)
        topPanel = header

        val label = UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        label.textAlignment = NSTextAlignmentCenter
        label.textColor = UIColor.whiteColor
        // Two lines: what the scan has measured, and which way the phone is pointing while it does
        // it. Wrapping rather than truncating also means a long phrase on a narrow phone is read
        // rather than cut off with a full stop nobody put there.
        label.numberOfLines = 0
        label.text = SCANNING_NOTHING_YET
        header.addSubview(label)
        counter = label

        val how = UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        how.textAlignment = NSTextAlignmentCenter
        how.textColor = UIColor.whiteColor
        how.font = UIFont.systemFontOfSize(INSTRUCTION_FONT)
        // A label shows one line and cuts the rest off with an ellipsis unless it is told
        // otherwise, and the whole of this one is the point of it.
        how.numberOfLines = 0
        how.text = howToScan
        header.addSubview(how)
        instructions = how

        // The section, small and out of the way in a corner. Behind the Done button's row rather
        // than beside it, since a surveyor sweeping the phone about is holding it by the edges.
        val panel = UIView(frame = CGRectMake(0.0, 0.0, PREVIEW_SIZE, PREVIEW_SIZE))
        panel.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(PANEL_DIMNESS)
        panel.layer.cornerRadius = PANEL_CORNER
        root.addSubview(panel)
        previewPanel = panel

        val wall = CAShapeLayer()
        wall.strokeColor = UIColor.whiteColor.CGColor
        wall.fillColor = null
        wall.lineWidth = PREVIEW_STROKE
        panel.layer.addSublayer(wall)
        preview = wall

        // Where the surveyor is, which is what makes a half-finished section readable: one wall
        // and a dot says "that wall is over there and you have not looked behind you".
        val station = CAShapeLayer()
        station.fillColor = UIColor.whiteColor.CGColor
        panel.layer.addSublayer(station)
        stationMark = station

        val done = UIButton.buttonWithType(UIButtonTypeSystem)
        done.setTitle(FINISH_TITLE, forState = UIControlStateNormal)
        done.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        done.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(BUTTON_DIMNESS)
        done.layer.cornerRadius = PANEL_CORNER
        done.addTarget(this, sel_registerName("finish"), UIControlEventTouchUpInside)
        root.addSubview(done)
        finishButton = done

        // A way off this screen that does not draw anything, which the swipe used to be until the
        // screen went full-size. Small, and over on the far side from Done: a mis-tap here throws
        // away a sweep, and the two ought not to be the same size or the same shape of target.
        val cancel = UIButton.buttonWithType(UIButtonTypeSystem)
        cancel.setTitle(CANCEL_TITLE, forState = UIControlStateNormal)
        cancel.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        cancel.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(BUTTON_DIMNESS)
        cancel.layer.cornerRadius = PANEL_CORNER
        cancel.addTarget(this, sel_registerName("cancelScan"), UIControlEventTouchUpInside)
        root.addSubview(cancel)
        cancelButton = cancel
    }

    /**
     * Laid out whenever the view is, which is once before it appears and again on every turn of
     * the phone or change of what the screen is saying.
     *
     * The view's own bounds rather than the screen's. They are the same thing here, since this is
     * presented full-size — but they are the same thing by a decision made in one other file, and
     * a screen whose Done button is off the bottom because somebody presented it differently is a
     * poor way to find that out.
     */
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val bounds = view?.bounds ?: return
        layOut(bounds.useContents { size.width }, bounds.useContents { size.height })
    }

    /**
     * Frames worked out rather than constrained.
     *
     * Auto Layout from Kotlin/Native means building `NSLayoutConstraint`s by hand, which is a lot
     * of interop for six things on a screen whose arrangement fits in twenty lines of arithmetic.
     * Run from viewDidLayoutSubviews, so it costs the same as a constraint would on a turn of the
     * phone and nothing at all the rest of the time.
     */
    private fun layOut(width: Double, height: Double) {
        // The camera is not in here: it is the size of the view it was made from and its
        // autoresizing mask keeps it that way, and a frame set on it twice a second — which is how
        // often this runs — is the one thing on the screen where that might be noticed.
        //
        // The panel is as tall as what is in it, asked of the labels themselves rather than
        // guessed at. A guess has to be generous enough for the longest sentence on the narrowest
        // phone, and anything less drops the end of a line without a word said — which for the
        // explanation would mean losing the part about how a scan stops, on the phone least able
        // to spare the room in the first place.
        val panelWidth = width - PANEL_MARGIN * 2
        val textWidth = panelWidth - PANEL_INSET * 2
        val counterHeight = heightOf(counter, textWidth)
        val instructionsHeight = heightOf(instructions, textWidth)
        topPanel?.setFrame(
            CGRectMake(
                PANEL_MARGIN,
                PANEL_TOP,
                panelWidth,
                PANEL_INSET * 2 + counterHeight + PANEL_GAP + instructionsHeight,
            ),
        )
        counter?.setFrame(CGRectMake(PANEL_INSET, PANEL_INSET, textWidth, counterHeight))
        instructions?.setFrame(
            CGRectMake(
                PANEL_INSET,
                PANEL_INSET + counterHeight + PANEL_GAP,
                textWidth,
                instructionsHeight,
            ),
        )

        // Cancel on the left and small, Done taking the rest of the row: the one that ends a scan
        // properly is the one a cold thumb should find without looking.
        val buttonsTop = height - BUTTONS_BOTTOM
        cancelButton?.setFrame(CGRectMake(PANEL_MARGIN, buttonsTop, CANCEL_WIDTH, BUTTON_HEIGHT))
        val doneLeft = PANEL_MARGIN * 2 + CANCEL_WIDTH
        finishButton?.setFrame(
            CGRectMake(doneLeft, buttonsTop, width - doneLeft - PANEL_MARGIN, BUTTON_HEIGHT),
        )

        previewPanel?.setFrame(
            CGRectMake(
                PANEL_MARGIN,
                buttonsTop - PREVIEW_SIZE - PANEL_MARGIN,
                PREVIEW_SIZE,
                PREVIEW_SIZE,
            ),
        )
    }

    /**
     * How tall a label has to be to show the whole of what it is holding, at this width.
     *
     * The height offered is a number no text will reach rather than a limit worth thinking about:
     * asking a label to fit itself into a bounded box gets an answer bounded by the box, which is
     * the truncation this exists to avoid.
     */
    private fun heightOf(label: UILabel?, width: Double): Double =
        label?.sizeThatFits(CGSizeMake(width, ROOM_FOR_ANY_TEXT))?.useContents { height } ?: 0.0

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
        // changes slowly — it needs a sweep's worth. Two a second is faster than a phone can be
        // swept, is a great deal less heat in a cold phone, and is a thirtieth of the frames to
        // borrow and hand back.
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

        // Every tick rather than once on the way in, and that is deliberate. The app underneath
        // holds the idle timer off for as long as it is composed, which is what stops a phone
        // locking itself in the middle of a survey — but this screen is presented over it full
        // size, and how a Compose composition behaves while its view is out of the window is a
        // detail of somebody else's library rather than a promise to this one. A scan with no
        // length limit on it is exactly the thing that would find out the hard way: the screen
        // sleeps, the session goes down, and a sweep that was going perfectly is gone. Setting a
        // boolean twice a second costs nothing and does not care what the answer is.
        UIApplication.sharedApplication.idleTimerDisabled = true

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

        // Not on every read, and not at all when nothing has been gathered since the last one.
        // Reducing the whole cloud is a pass over every point kept so far, and a surveyor cannot
        // sweep a phone fast enough for twice a second to say anything once a second does not.
        if (samples % READS_PER_PREVIEW == 0 && gathered.size != drawnFrom) {
            drawnFrom = gathered.size
            drawTheSectionSoFar()
        }

        sayWhatToDo()

        counter?.text =
            when {
                // No bearing with it: what the pose last said is as stale as the picture, and a
                // stopped camera is the one thing on this screen worth a line to itself.
                stalledReads * SAMPLE_SECONDS >= STALL_SECONDS -> CAMERA_STOPPED
                gathered.size >= SCAN_POINT_LIMIT -> scanFull(directionsMeasured) + facing()
                gathered.isEmpty() -> SCANNING_NOTHING_YET + facing()
                else -> scanningProgress(directionsMeasured, usesDepth) + facing()
            }

        // Both labels have just been written, and either may have changed how many lines it wants
        // — the explanation coming down is several, and a count that wraps on a narrow phone is
        // one. Asking for a layout is how the panel round them stays the size of what it holds;
        // UIKit does at most one of them however often this is asked.
        view?.setNeedsLayout()
    }

    /**
     * Which way the phone is pointing and which way the passage runs, on a line of their own.
     *
     * Both, because one without the other is a number to be remembered and the pair is a check
     * that needs nothing but the screen: point the phone along the passage and they should agree,
     * give or take the declination and however straight the passage is. The phone's own drops out
     * when it is aimed within a few degrees of straight up or down, where there is no bearing to
     * report and a number spinning round the compass while the phone is held still would be read
     * as the compass being broken.
     */
    private fun facing(): String = "\n" + facingSays(pointingAt, bearing)

    /**
     * Say what to do next, and make room for it if the amount to say has changed.
     *
     * Three things in the order a surveyor needs them: the whole explanation while they are
     * getting started, then the one line that says how a scan ends, and — for a scan that has been
     * running long enough that it might have been forgotten about — how long it has been going.
     * That last one is what the half-minute cut-off used to do, without the half of it that threw
     * a sweep away: a phone left scanning in a pocket is a light and a lidar and a screen that
     * will not sleep, which underground is the walk out.
     */
    private fun sayWhatToDo() {
        val scanned = samples * SAMPLE_SECONDS
        instructions?.text =
            when {
                // Both, and not just the first: a phone opened facing a wall measures a direction
                // within a second or two, and an explanation that vanished that fast would be one
                // nobody ever read.
                directionsMeasured == 0 || scanned < EXPLAIN_SECONDS -> howToScan
                scanned >= LONG_SCAN_SECONDS -> stillRunning(scanned)
                else -> TAP_DONE_WHEN_READY
            }
    }

    /**
     * Reduce what has been gathered to a section, draw it in the corner, and tap if it grew.
     *
     * One pass over the points for both answers, which is why `PassageScan.strokesFrom` is public:
     * the wall distances give the strokes to draw *and* the count of directions measured, and
     * asking `outlines` for the first and `wallDistances` for the second would walk the cloud twice.
     *
     * The fitting is `ScanPreview`'s rather than this file's, for the reason the un-projection is
     * `DepthCamera`'s: a scale worked out from the walls alone, or a y flipped once too often,
     * draws a plausible section of somewhere else. Here there is nothing but the handover.
     */
    private fun drawTheSectionSoFar() {
        val walls = PassageScan.wallDistances(gathered, bearing)
        val measured = ScanPreview.sectorsMeasured(walls)
        if (measured > directionsMeasured) {
            directionsMeasured = measured
            // Prepared and fired together. The generator warms the motor when it is prepared, and
            // preparing it on a tick that is about to fire it is the cheapest way to get the tap
            // promptly without holding the hardware awake for the whole of a scan.
            haptics.prepare()
            haptics.impactOccurred()
        }

        val fitted =
            ScanPreview.fit(
                PassageScan.strokesFrom(walls),
                PREVIEW_SIZE.toFloat(),
                PREVIEW_INSET.toFloat(),
            )

        val path = UIBezierPath()
        for (stroke in fitted.strokes) {
            val first = stroke.firstOrNull() ?: continue
            path.moveToPoint(CGPointMake(first.x.toDouble(), first.y.toDouble()))
            for (point in stroke.drop(1)) {
                path.addLineToPoint(CGPointMake(point.x.toDouble(), point.y.toDouble()))
            }
        }
        preview?.path = path.CGPath

        val dot =
            UIBezierPath.bezierPathWithOvalInRect(
                CGRectMake(
                    fitted.station.x.toDouble() - STATION_DOT / 2,
                    fitted.station.y.toDouble() - STATION_DOT / 2,
                    STATION_DOT,
                    STATION_DOT,
                ),
            )
        stationMark?.path = dot.CGPath
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
     * Two things come out of a frame besides points. Its timestamp, handed back, which is how the
     * caller tells a stopped camera from a quiet one — null when there is no frame yet. And the
     * bearing the camera is pointing on, which is put in a field rather than returned because it
     * is for the surveyor to read rather than for this loop to act on.
     */
    private fun readCurrentFrame(): Double? {
        val frame = arView?.session?.currentFrame ?: return null
        pointingAt =
            DepthCamera.bearingOf(
                frame.camera.transform.floatsOfStruct(DepthCamera.TRANSFORM_FLOATS),
            )
        // A full scan still borrows and returns its frame, because the timestamp is what tells a
        // stopped camera from a still one and that is worth knowing either way. What it stops doing
        // is walking fifty thousand pixels for points it is not allowed to keep.
        if (gathered.size < SCAN_POINT_LIMIT) {
            if (usesDepth) gatherFromDepth(frame) else gatherFromFeaturePoints(frame)
        }
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
     * Leave without drawing anything.
     *
     * The scan that went wrong — the wrong station, a phone that never found north, a sweep
     * interrupted by the rest of the trip — and the answer to it being on the drawing at all. It
     * exists because the screen is now presented full-size, which took away the swipe that used to
     * serve; and that swipe was a poor way to do this anyway, since it could as easily be an
     * accident as a decision.
     */
    @Suppress("unused")
    @ObjCAction
    fun cancelScan() {
        if (finished) return
        finished = true
        timer?.invalidate()
        timer = null
        arView?.session?.pause()
        dismissViewControllerAnimated(true, null)
    }

    /**
     * Hand the scan over and get off the screen.
     *
     * Called by the button through `sel_registerName`, and by nothing else: a scan ends when the
     * surveyor ends it. Still written to be safe called twice, because a button pressed twice in
     * the dark with cold hands is one press as far as the surveyor is concerned, and losing a
     * sweep to that would be a poor joke. Stopping the timer first is what makes it safe.
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

/**
 * Presented over the whole screen, rather than as the card iOS would choose.
 *
 * The *other* kind of `NS_ENUM`, and a second entry for the note above: `ARWorldAlignment` came
 * across as an enum class, and `UIModalPresentationStyle` comes across as a bare constant, which
 * is what `CBManagerStatePoweredOn` does in `CoreBluetoothTransport`. Written the first way, and
 * the macOS runner said "Unresolved reference 'UIModalPresentationFullScreen'" — the two kinds
 * cannot be told apart from Linux, and the coin lands both ways within one file.
 *
 * What it is for is on `ArKitScanner.scan`: a card can be wiped away by a drag, and a scan is now
 * as long as the surveyor wants it to be.
 */
private val FULL_SCREEN = UIModalPresentationFullScreen

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
 *
 * Inline and reified because `useContents` is: it has to place the value somewhere of a size it
 * knows, so the type cannot be an ordinary type parameter and has to be carried through to here.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <reified T : CStructVar> CValue<T>.floatsOfStruct(count: Int): FloatArray =
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
 * The live section's panel: how big, how far in from the corner, and how it is drawn.
 *
 * Points rather than pixels, as every frame on this screen is. Big enough to read a passage shape
 * at arm's length in the dark, small enough to leave the camera worth looking at — the surveyor is
 * aiming the phone at rock, and a preview that covered the view would defeat the sweep it exists
 * to guide.
 */
private const val PREVIEW_SIZE = 150.0

/** Inside the panel, so the outermost wall cannot be mistaken for the panel's own edge. */
private const val PREVIEW_INSET = 10.0

private const val PREVIEW_STROKE = 2.0

/**
 * The two dim panels: how far in from the screen they sit, how dark, and how round.
 *
 * One set of numbers for the words at the top and the drawing at the bottom, because they are the
 * same thing twice — something laid over a camera picture that has to be readable against whatever
 * the camera happens to be looking at.
 */
private const val PANEL_MARGIN = 20.0

private const val PANEL_DIMNESS = 0.45

private const val PANEL_CORNER = 10.0

/** Inside the top panel, between its edge and its text. */
private const val PANEL_INSET = 10.0

/** Below the status bar and clear of the notch, on the phones this will be held in. */
private const val PANEL_TOP = 60.0

/** Between the count and the words under it, so the two are read as two things. */
private const val PANEL_GAP = 6.0

/** Smaller than the count above it: instructions are read once, and a count is glanced at. */
private const val INSTRUCTION_FONT = 14.0

/** Taller than any label will ask for, so that fitting a label to a width bounds nothing else. */
private const val ROOM_FOR_ANY_TEXT = 10_000.0

/**
 * The row of buttons: how far up from the bottom, how tall, and how much of it Cancel gets.
 *
 * Clear of the home indicator, and clear of the bottom of the screen by more than a thumb's width,
 * because the phone is being held by its edges and swept about. Cancel takes a little over a third
 * of the smallest screen this will run on and less of a big one, which is the way round it should
 * be: Done grows, and the button that throws a sweep away does not.
 */
private const val BUTTONS_BOTTOM = 120.0

private const val BUTTON_HEIGHT = 56.0

private const val CANCEL_WIDTH = 110.0

private const val BUTTON_DIMNESS = 0.6

/** The surveyor's own mark, which is what makes a section with one wall in it readable. */
private const val STATION_DOT = 6.0

/**
 * How many reads go by between drawings of the section.
 *
 * Every read is cheap — a walk of the depth picture — but reducing the whole cloud to a section is
 * a pass over every point kept so far, and that grows all scan. Once a second is faster than a
 * surveyor can sweep a phone and slower than the arithmetic costs.
 */
private const val READS_PER_PREVIEW = 2

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
 * How long the explanation stays up, however quickly the scan starts measuring.
 *
 * It comes down when the section has a direction in it *and* this much has gone by, and the second
 * half is what makes it readable: a phone opened facing a wall measures its first direction within
 * a second or two, so on the first condition alone the explanation would be gone before anybody
 * had read a line of it. Fifteen seconds is a slow read of three sentences.
 */
private const val EXPLAIN_SECONDS = 15.0

/**
 * How long a scan runs before the screen mentions how long it has been running.
 *
 * The half of the old half-minute cut-off worth keeping. A forgotten scan is a camera, a lidar and
 * a screen that will not sleep, all running in somebody's pocket on the battery they get out on —
 * which is what that cut-off was for, and it went because it also ended scans that were going
 * perfectly well. Saying so costs a surveyor who is deliberately taking their time nothing at all,
 * and three minutes is far longer than any sweep that is going anywhere.
 */
private const val LONG_SCAN_SECONDS = 180.0

/**
 * The wording, typed here rather than mirrored from `strings.xml`.
 *
 * `Strings.local` exists for exactly this case and cannot be used from `iosMain`: this screen is
 * UIKit rather than Compose, so it is built before anything Compose knows about is on screen. The
 * Android app has no scanner and so no resource to be held to, which is what makes typing them out
 * honest rather than lazy — see the same note on `whyNoCamera`.
 */
private const val SCANNING_NOTHING_YET = "Nothing measured yet"

/**
 * What the scan is and how it is worked, on the screen where it is worked.
 *
 * A surveyor meets this once a trip at most, so nothing about it is learnt by repetition, and the
 * gesture it wants is not one a camera screen implies: the phone is being swept over rock like a
 * torch rather than aimed at it like a camera, and the roof and the floor count as much as the
 * walls. The manual is the obvious place to say so and cannot be — `manual.html` is shared with the
 * Android app, which has no scanner — so this screen says it.
 *
 * Four things, in the order they are needed: where the section is drawn from, how to sweep, what
 * the outline in the corner is for, and what ends the scan. The last is not a nicety now that
 * nothing else does end one.
 *
 * The first is a limitation stated as an instruction, which is the honest way round while it is
 * still a limitation. ARKit's origin is wherever the device was when the session started — which
 * is when this screen appeared — and the drawing treats that as the station, so a scan begun two
 * metres away is a good section of the passage drawn two metres out of place. Saying where the
 * origin *is* rather than "stand at the station" also tells a surveyor what to do about the
 * vertical half of it, which standing anywhere does not fix: a station on the floor is a metre and
 * a half below a phone held at the chest.
 */
private const val HOW_TO_SCAN =
    "The section is drawn from where the phone was when this opened, so start at the station. " +
        "Sweep slowly over the walls, the roof and the floor: the outline below fills in as you " +
        "go, and the gaps are what is left. Take as long as you need, then tap Done to draw it."

/** Said to a lidar phone, because the gap a chamber leaves is otherwise a mystery to sweep at. */
private const val LIDAR_RANGE =
    "Rock more than five metres off is not measured, so a big chamber comes back with gaps."

/** And to a phone without one, where the limit is a different thing entirely. */
private const val TRACKING_NEEDS_LIGHT =
    "Without lidar this phone measures only rock it can pick out detail on, so keep the light on " +
        "the wall and sweep slowly."

/** What is left of the above once the surveyor is plainly scanning. */
private const val TAP_DONE_WHEN_READY = "Fill in the gaps, then tap Done to draw the section."

/**
 * Said to a scan that has been going for a while, in case it is going on without anybody.
 *
 * Minutes rather than seconds, and rounded down, because the number is not a measurement of
 * anything — it is there to be recognised as larger than expected by somebody who has just taken
 * their phone out of a pocket.
 */
private fun stillRunning(seconds: Double): String =
    "Still scanning after ${(seconds / SECONDS_PER_MINUTE).toInt()} minutes - tap Done when you " +
        "have finished."

private const val SECONDS_PER_MINUTE = 60.0

/**
 * Which way the phone is pointing according to the scan, and which way the passage runs.
 *
 * Worth a line of a small screen because it is the only part of a scan a surveyor can check while
 * they are still standing where it was taken. Everything measured is placed relative to ARKit's
 * idea of north; a north out by a quarter turn draws a good section of the wrong plane, and
 * nothing about the drawing says so. The passage's own bearing is the thing to check it against,
 * so it is put beside it rather than left to be remembered: point the phone along the passage, and
 * the two numbers should agree.
 *
 * A degree or two of disagreement is the declination — the scan's north is true and the survey's
 * is magnetic — and is expected, as is however straight the passage is between two stations. A
 * quarter turn of it is not, and means the scan is measuring the wrong plane.
 */
private fun facingSays(pointing: Float?, passage: Float): String =
    if (pointing == null) {
        "Passage ${asBearing(passage)}"
    } else {
        "Phone facing ${asBearing(pointing)}, passage ${asBearing(passage)}"
    }

/**
 * A bearing written the way a survey writes one: three figures, and the degree sign.
 *
 * Three figures because that is what is in the book and on the instrument, and a number meant to be
 * compared with those at a glance in the dark should not need the comparison done twice.
 */
private fun asBearing(degrees: Float): String {
    val whole = ((degrees.roundToInt() % FULL_TURN_DEGREES) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
    return whole.toString().padStart(BEARING_FIGURES, '0') + "°"
}

private const val FULL_TURN_DEGREES = 360

private const val BEARING_FIGURES = 3

private const val FINISH_TITLE = "Done"

private const val CANCEL_TITLE = "Cancel"

/**
 * Said when ARKit has stopped producing frames, which a stuck count on its own does not mean.
 *
 * A surveyor holding still finds no new rock and the count stops, and that is the scan working. The
 * difference is invisible from the outside, and the wrong reading of it costs a trip: a whole sweep
 * of a dead camera, or an abandoned scan that was only quiet. The first of those got worse when the
 * half-minute cut-off went, since nothing now ends a scan of nothing except the surveyor deciding
 * it has gone on long enough.
 */
private const val CAMERA_STOPPED = "The camera has stopped - tap Done and scan again"

/**
 * How much of the section is done, and which sensor is doing it.
 *
 * Directions rather than points, and the difference is the whole reason the preview exists: a point
 * count rises just as fast for a surveyor sweeping one wall over and over as for one who has been
 * the whole way round, so it says the sensor is alive and nothing about whether the passage has
 * been covered. Sixty is `PassageScan.DEFAULT_SECTORS`, which is what decides where the drawn
 * strokes break, so the number and the picture beneath it are the same measurement.
 *
 * The sensor is still named, because it is the one thing about a scan that cannot be worked out
 * afterwards from what it drew: a thin wall from a phone with no lidar is the sparse cloud doing
 * its best, and the same thin wall from one with lidar is a bug.
 */
private fun scanningProgress(directions: Int, lidar: Boolean): String =
    "$directions of ${PassageScan.DEFAULT_SECTORS} directions (" +
        (if (lidar) "lidar" else "tracking") + ")"

/**
 * Said when the scan is holding as many points as it will hold.
 *
 * The count stays in front of the wording rather than being replaced by it, because what to do next
 * turns on it: full with fifty directions in it is a finished job, and full with twelve is a scan
 * that filled itself up on a wall a hand's breadth from the lens. Either way the scan stays open —
 * the drawing is there to be looked at, and ending it for them is the thing this screen no longer
 * does.
 */
private fun scanFull(directions: Int): String =
    "$directions of ${PassageScan.DEFAULT_SECTORS} directions - full, tap Done"
