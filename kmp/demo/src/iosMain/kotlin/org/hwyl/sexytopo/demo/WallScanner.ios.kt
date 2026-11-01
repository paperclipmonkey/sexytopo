package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.scan.PenLine
import org.hwyl.sexytopo.shared.scan.PenStroke
import org.hwyl.sexytopo.shared.scan.ScanRecovery
import org.hwyl.sexytopo.shared.scan.StationTouch
import org.hwyl.sexytopo.shared.scan.SurfaceTiles
import org.hwyl.sexytopo.shared.scan.SymbolStamp
import org.hwyl.sexytopo.shared.scan.WallScan
import org.hwyl.sexytopo.shared.scan.touchPointInScan
import org.hwyl.sexytopo.shared.sketch.DepthCamera
import org.hwyl.sexytopo.shared.sketch.ScanPreview
import platform.ARKit.ARFrame
import platform.ARKit.ARFrameSemanticNone
import platform.ARKit.ARFrameSemanticSceneDepth
import platform.ARKit.ARFrameSemanticSmoothedSceneDepth
import platform.ARKit.ARFrameSemantics
import platform.ARKit.ARSCNView
import platform.ARKit.ARTrackingState
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
import platform.QuartzCore.CAShapeLayer
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIBezierPath
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchCancel
import platform.UIKit.UIControlEventTouchDown
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlEventTouchUpOutside
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UISlider
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.darwin.sel_registerName
import kotlin.math.roundToInt
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The wall scanner on iOS: ARKit's camera with a crosshair in the middle, and the controls to
 * mark stations, draw with the AR pen and stamp symbols, all while the LiDAR quietly records the
 * rock around the surveyor.
 *
 * Built the way `PassageScanner.ios.kt` is, and leaning on what that file found the hard way:
 * frames are read on a timer rather than through a session delegate, the Kotlin garbage collector
 * is run after every read so ARKit gets its frames back, and the depth map is turned into points by
 * `DepthCamera`, whose arithmetic is tested in the shared module. Where this file reads the depth
 * map it copies that file's walk of the pixel buffer rather than inventing another.
 *
 * ## What the surveyor does here
 *
 * - **Mark a station**, two ways. *Aim*: put the crosshair on the station's mark from a metre or
 *   so away and tap; the LiDAR says where the mark is. *Touch*: hold the camera lens against the
 *   mark and tap; the lens is where the phone is. Two marked stations anchor the scan.
 * - **Draw with the pen**: hold *Draw* and sweep the crosshair along a wall edge, a water line or a
 *   ledge. The slider smooths the last five metres of the line; *Wall* or *Water* picks its colour.
 * - **Stamp a symbol**: pick one with the symbol button, aim, and tap *Stamp*. A directional
 *   symbol such as water flow points the way the phone faces.
 * - **Done** hands everything back; **Cancel** throws it away.
 *
 * ## Status: compiled, not yet run on a phone
 *
 * Compiled for the simulator and linked into the app with Xcode, which is as far as a machine
 * without a lidar phone can take it: the simulator has no ARKit depth, so this screen reports
 * itself unavailable there and the review panel's simulated scans stand in. Everything it calls
 * into in the shared module is tested on the JVM; what is unverified is how it behaves with a real
 * depth map, which is why it reuses the passage scanner's own calls wherever it can.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberWallScanner(onScanned: (WallScan) -> Boolean): WallScanner {
    val deliver = rememberUpdatedState(onScanned)
    return remember { ArKitWallScanner { scan -> deliver.value(scan) } }
}

@OptIn(ExperimentalForeignApi::class)
private class ArKitWallScanner(private val onScanned: (WallScan) -> Boolean) : WallScanner {

    /**
     * LiDAR only. The pen, the aim and the walls all read the depth map; a phone without one
     * would offer a screen on which nothing works.
     */
    override val available: Boolean =
        ARWorldTrackingConfiguration.isSupported() && WALL_DEPTH_SEMANTIC != ARFrameSemanticNone

    /** Held for the reason `ArKitScanner` holds its own: ARKit's north depends on location. */
    private val location = CLLocationManager()

    override fun scan(surveyName: String, stations: List<String>, startStation: String?) {
        if (!available) return
        location.requestWhenInUseAuthorization()
        val host = topmostViewController() ?: return
        // The app has already given any unfinished scan to a survey before calling this, so what
        // is cleared here is only what that left behind.
        runCatching { ScanRecovery.start(surveyFileStore, surveyName) }
        val screen = WallScanViewController(stations, startStation, onScanned)
        screen.modalPresentationStyle = WALL_FULL_SCREEN
        host.presentViewController(screen, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, NativeRuntimeApi::class)
private class WallScanViewController(
    private val stations: List<String>,
    private val startStation: String?,
    private val onScanned: (WallScan) -> Boolean,
) : UIViewController(nibName = null, bundle = null) {

    /**
     * Tiles behind the surveyor go to files, so memory stays bounded however long the walk; the
     * same files are what [ScanRecovery] reads back if the app stops before Done.
     */
    private val tiles = SurfaceTiles(WALL_VOXEL_METRES, ScanRecovery.tileSink(surfaceStore()))
    private var samplesSinceCheckpoint = 0
    private val path = mutableListOf<Coord3D>()
    private val poorTracking = mutableSetOf<Int>()
    private val touches = mutableListOf<StationTouch>()
    private val penLines = mutableListOf<PenLine>()
    private val stamps = mutableListOf<SymbolStamp>()

    private var stationIndex = maxOf(0, stations.indexOf(startStation ?: ""))
    private var symbolIndex = 0
    private var drawingWater = false
    private var smoothing = PenStroke.DEFAULT_SMOOTHING

    /** The line being drawn, while *Draw* is held. */
    private var activeStroke: PenStroke? = null

    private var arView: ARSCNView? = null
    private var statusPanel: UIView? = null
    private var status: UILabel? = null
    private var previewPanel: UIView? = null
    private var preview: CAShapeLayer? = null
    private var crosshair: CAShapeLayer? = null
    private val buttons = mutableMapOf<String, UIButton>()
    private var slider: UISlider? = null
    private var sliderLabel: UILabel? = null

    private var sampleTimer: NSTimer? = null
    private var penTimer: NSTimer? = null
    private var finished = false
    private var lastSaid: String? = null
    private var tracking = ""
    private var aimingAt: Float? = null

    private val haptics = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

    override fun viewDidLoad() {
        super.viewDidLoad()
        val root = view ?: return

        val camera = ARSCNView(frame = root.bounds)
        camera.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        root.addSubview(camera)
        arView = camera

        val cross = CAShapeLayer()
        cross.strokeColor = UIColor.yellowColor.CGColor
        cross.fillColor = null
        cross.lineWidth = CROSSHAIR_STROKE
        root.layer.addSublayer(cross)
        crosshair = cross

        val panel = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        panel.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(WALL_PANEL_DIMNESS)
        panel.layer.cornerRadius = WALL_PANEL_CORNER
        root.addSubview(panel)
        statusPanel = panel

        val label = UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        label.textAlignment = NSTextAlignmentCenter
        label.textColor = UIColor.whiteColor
        label.font = UIFont.systemFontOfSize(STATUS_FONT)
        label.numberOfLines = 0
        label.text = START_SAYING
        panel.addSubview(label)
        status = label

        val map = UIView(frame = CGRectMake(0.0, 0.0, WALL_PREVIEW_SIZE, WALL_PREVIEW_SIZE))
        map.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(WALL_PANEL_DIMNESS)
        map.layer.cornerRadius = WALL_PANEL_CORNER
        root.addSubview(map)
        previewPanel = map

        val lines = CAShapeLayer()
        lines.strokeColor = UIColor.whiteColor.CGColor
        lines.fillColor = null
        lines.lineWidth = WALL_PREVIEW_STROKE
        map.layer.addSublayer(lines)
        preview = lines

        addButton(BUTTON_PREVIOUS, "<", "previousStation")
        addButton(BUTTON_STATION, "", "noOp")
        addButton(BUTTON_NEXT, ">", "nextStation")
        addButton(BUTTON_AIM, "Aim", "aimStation")
        addButton(BUTTON_TOUCH, "Touch", "touchStation")
        addButton(BUTTON_KIND, "", "toggleKind")
        addButton(BUTTON_SYMBOL, "", "nextSymbol")
        addButton(BUTTON_STAMP, "Stamp", "stamp")
        addButton(BUTTON_CANCEL, "Cancel", "cancelScan")
        addButton(BUTTON_DONE, "Done", "finish")

        // Draw is held, not tapped: down starts a line and any way of letting go ends it.
        val draw = addButton(BUTTON_DRAW, "Hold to draw", "noOp")
        draw.addTarget(this, sel_registerName("penDown"), UIControlEventTouchDown)
        draw.addTarget(
            this,
            sel_registerName("penUp"),
            UIControlEventTouchUpInside or UIControlEventTouchUpOutside or UIControlEventTouchCancel,
        )

        val smooth = UISlider(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        smooth.minimumValue = 0f
        smooth.maximumValue = 1f
        smooth.value = smoothing
        smooth.addTarget(this, sel_registerName("smoothingChanged"), UIControlEventValueChanged)
        root.addSubview(smooth)
        slider = smooth

        val smoothLabel = UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
        smoothLabel.textColor = UIColor.whiteColor
        smoothLabel.font = UIFont.systemFontOfSize(STATUS_FONT)
        // Backed like the buttons: white on a bright wall, or on the Simulator's blank camera, is
        // no label at all. Found by opening this screen in the Simulator.
        smoothLabel.textAlignment = NSTextAlignmentCenter
        smoothLabel.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(WALL_BUTTON_DIMNESS)
        smoothLabel.layer.cornerRadius = WALL_PANEL_CORNER
        smoothLabel.layer.masksToBounds = true
        root.addSubview(smoothLabel)
        sliderLabel = smoothLabel

        refreshLabels()
    }

    private fun addButton(name: String, title: String, action: String): UIButton {
        val button = UIButton.buttonWithType(UIButtonTypeSystem)
        button.setTitle(title, forState = UIControlStateNormal)
        button.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        button.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(WALL_BUTTON_DIMNESS)
        button.layer.cornerRadius = WALL_PANEL_CORNER
        button.titleLabel?.font = UIFont.systemFontOfSize(BUTTON_FONT)
        if (action != "noOp") {
            button.addTarget(this, sel_registerName(action), UIControlEventTouchUpInside)
        }
        view?.addSubview(button)
        buttons[name] = button
        return button
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val root = view ?: return
        val bounds = root.bounds
        // Clear of the notch or Dynamic Island at the top and the home indicator at the bottom,
        // which on a Pro iPhone are taller than any fixed margin that also suits an iPad.
        val (top, bottom) = root.safeAreaInsets.useContents { this.top to this.bottom }
        layOut(
            bounds.useContents { size.width },
            bounds.useContents { size.height },
            maxOf(WALL_TOP, top + WALL_GAP),
            maxOf(WALL_BOTTOM, bottom + WALL_GAP),
        )
    }

    /**
     * Four rows of controls along the bottom, thumb-height, and the status and map at the top.
     * Laid out by hand, as the passage scanner's screen is, because there is no storyboard.
     */
    private fun layOut(width: Double, height: Double, topMargin: Double, bottomMargin: Double) {
        val inner = width - WALL_MARGIN * 2

        val textWidth = inner - WALL_INSET * 2
        val textHeight =
            status?.sizeThatFits(CGSizeMake(textWidth, WALL_ROOM_FOR_ANY_TEXT))?.useContents { this.height } ?: 0.0
        statusPanel?.setFrame(CGRectMake(WALL_MARGIN, topMargin, inner, textHeight + WALL_INSET * 2))
        status?.setFrame(CGRectMake(WALL_INSET, WALL_INSET, textWidth, textHeight))
        previewPanel?.setFrame(
            CGRectMake(
                width - WALL_MARGIN - WALL_PREVIEW_SIZE,
                topMargin + textHeight + WALL_INSET * 2 + WALL_GAP,
                WALL_PREVIEW_SIZE,
                WALL_PREVIEW_SIZE,
            ),
        )

        val centreX = width / 2
        val centreY = height / 2
        crosshair?.path =
            UIBezierPath.bezierPathWithOvalInRect(
                CGRectMake(centreX - CROSSHAIR_SIZE / 2, centreY - CROSSHAIR_SIZE / 2, CROSSHAIR_SIZE, CROSSHAIR_SIZE),
            ).CGPath

        fun row(index: Int): Double = height - bottomMargin - ROW_HEIGHT - (ROWS - 1 - index) * (ROW_HEIGHT + WALL_GAP)

        fun place(names: List<String>, weights: List<Double>, top: Double) {
            val total = weights.sum()
            val room = inner - WALL_GAP * (names.size - 1)
            var left = WALL_MARGIN
            for ((name, weight) in names.zip(weights)) {
                val w = room * weight / total
                buttons[name]?.setFrame(CGRectMake(left, top, w, ROW_HEIGHT))
                left += w + WALL_GAP
            }
        }

        place(
            listOf(BUTTON_PREVIOUS, BUTTON_STATION, BUTTON_NEXT, BUTTON_AIM, BUTTON_TOUCH),
            listOf(0.6, 1.4, 0.6, 1.0, 1.0),
            row(0),
        )
        place(listOf(BUTTON_DRAW, BUTTON_KIND), listOf(2.0, 1.0), row(1))
        sliderLabel?.setFrame(CGRectMake(WALL_MARGIN, row(2), SLIDER_LABEL_WIDTH, ROW_HEIGHT))
        slider?.setFrame(
            CGRectMake(
                WALL_MARGIN + SLIDER_LABEL_WIDTH + WALL_GAP,
                row(2),
                inner - SLIDER_LABEL_WIDTH - WALL_GAP,
                ROW_HEIGHT,
            ),
        )
        place(listOf(BUTTON_SYMBOL, BUTTON_STAMP), listOf(2.0, 1.0), row(3))
        place(listOf(BUTTON_CANCEL, BUTTON_DONE), listOf(1.0, 2.0), row(4))
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        val configuration = ARWorldTrackingConfiguration()
        configuration.worldAlignment = WALL_GRAVITY_AND_HEADING
        configuration.frameSemantics = WALL_DEPTH_SEMANTIC
        arView?.session?.runWithConfiguration(configuration)
        sampleTimer = NSTimer.scheduledTimerWithTimeInterval(interval = WALL_SAMPLE_SECONDS, repeats = true) { _ -> sample() }
    }

    /** Twice a second: gather rock, note where the phone is, keep memory bounded, say how it is going. */
    private fun sample() {
        UIApplication.sharedApplication.idleTimerDisabled = true
        val frame = arView?.session?.currentFrame
        if (frame != null) {
            val transform = frame.camera.transform.wallFloats(DepthCamera.TRANSFORM_FLOATS)
            val here = Coord3D(transform[12], -transform[14], transform[13])
            path.add(here)
            tiles.moveTo(here.x, here.y, here.z)
            tracking =
                when (frame.camera.trackingState) {
                    ARTrackingState.ARTrackingStateNormal -> ""
                    ARTrackingState.ARTrackingStateLimited -> TRACKING_LIMITED
                    else -> TRACKING_LOST
                }
            if (tracking.isNotEmpty()) poorTracking.add(path.size - 1)
            gatherDepth(frame)
            aimingAt = centreHit(frame)?.let { hit -> distanceBetween(hit, here) }
        }
        GC.collect()
        if (++samplesSinceCheckpoint >= WALL_CHECKPOINT_SAMPLES) {
            samplesSinceCheckpoint = 0
            checkpoint()
        }
        refreshLabels()
        drawPreview()
    }

    /**
     * Writes the scan down as it stands, so a crash or a flat battery loses seconds rather than
     * the trip. Failing to is not worth stopping a scan for: the scan in memory is still whole.
     */
    private fun checkpoint() {
        runCatching {
            tiles.flush()
            val lines = penLines.toMutableList()
            activeStroke?.let { if (it.rawHits.size >= 2) lines.add(PenLine(it, penColour())) }
            ScanRecovery.checkpoint(
                surfaceStore(),
                path.toList(),
                touches.toList(),
                lines,
                stamps.toList(),
                startStation,
                poorTracking.toSet(),
            )
        }
    }

    private fun penColour(): Colour = if (drawingWater) Colour.BLUE else Colour.BLACK

    /** Ten times a second while *Draw* is held: one pen hit from the crosshair. */
    private fun penSample() {
        val stroke = activeStroke ?: return
        val frame = arView?.session?.currentFrame
        val hit = frame?.let { centreHit(it) }
        GC.collect()
        if (hit != null) stroke.add(hit)
    }

    /**
     * The point on the rock under the crosshair, from the middle of the depth map: the median of
     * a small patch, so a single bad pixel at the centre does not throw the pen a metre.
     *
     * The middle of the depth map is the middle of the camera image, and ARSCNView fills the
     * screen from the middle of that image, so the crosshair drawn at the screen's centre is
     * looking at this pixel whichever way up the phone is held.
     */
    private fun centreHit(frame: ARFrame): Coord3D? {
        val depth = frame.smoothedSceneDepth ?: frame.sceneDepth ?: return null
        val map = depth.depthMap ?: return null
        val camera = frame.camera
        val (imageWidth, imageHeight) =
            camera.imageResolution.useContents { width.roundToInt() to height.roundToInt() }
        if (imageWidth <= 0 || imageHeight <= 0) return null

        CVPixelBufferLockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
        try {
            val width = CVPixelBufferGetWidth(map).toInt()
            val height = CVPixelBufferGetHeight(map).toInt()
            if (width <= 0 || height <= 0) return null
            val bytes = CVPixelBufferGetBaseAddress(map)?.reinterpret<ByteVar>() ?: return null
            val rowBytes = CVPixelBufferGetBytesPerRow(map).toInt()
            val column = width / 2
            val row = height / 2
            val patch = mutableListOf<Float>()
            for (r in row - CENTRE_PATCH..row + CENTRE_PATCH) {
                val depths = (bytes + r.toLong() * rowBytes)!!.reinterpret<FloatVar>()
                for (c in column - CENTRE_PATCH..column + CENTRE_PATCH) {
                    val metres = depths[c]
                    if (metres.isFinite() && metres >= WALL_NEAREST_METRES && metres <= WALL_FURTHEST_METRES) {
                        patch.add(metres)
                    }
                }
            }
            if (patch.size < CENTRE_MINIMUM) return null
            patch.sort()
            val lens =
                DepthCamera.forDepthImage(
                    intrinsics = camera.intrinsics.wallFloats(DepthCamera.INTRINSICS_FLOATS),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    depthWidth = width,
                    depthHeight = height,
                    transform = camera.transform.wallFloats(DepthCamera.TRANSFORM_FLOATS),
                )
            return lens.pointAt(column, row, patch[patch.size / 2])
        } finally {
            CVPixelBufferUnlockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
        }
    }

    /** The passage scanner's walk of the depth map, into the tiles instead of a list. */
    private fun gatherDepth(frame: ARFrame) {
        val depth = frame.smoothedSceneDepth ?: frame.sceneDepth ?: return
        val map = depth.depthMap ?: return
        val confidence = depth.confidenceMap
        val camera = frame.camera
        val (imageWidth, imageHeight) =
            camera.imageResolution.useContents { width.roundToInt() to height.roundToInt() }
        if (imageWidth <= 0 || imageHeight <= 0) return

        CVPixelBufferLockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
        if (confidence != null) CVPixelBufferLockBaseAddress(confidence, kCVPixelBufferLock_ReadOnly)
        try {
            val width = CVPixelBufferGetWidth(map).toInt()
            val height = CVPixelBufferGetHeight(map).toInt()
            if (width <= 0 || height <= 0) return
            val depthBytes = CVPixelBufferGetBaseAddress(map)?.reinterpret<ByteVar>() ?: return
            val depthRowBytes = CVPixelBufferGetBytesPerRow(map).toInt()
            val gradeBytes = confidence?.let { CVPixelBufferGetBaseAddress(it)?.reinterpret<ByteVar>() }
            val gradeRowBytes = confidence?.let { CVPixelBufferGetBytesPerRow(it).toInt() } ?: 0
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
                    intrinsics = camera.intrinsics.wallFloats(DepthCamera.INTRINSICS_FLOATS),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    depthWidth = width,
                    depthHeight = height,
                    transform = camera.transform.wallFloats(DepthCamera.TRANSFORM_FLOATS),
                )
            var row = 0
            while (row < height) {
                val depths = (depthBytes + row.toLong() * depthRowBytes)!!.reinterpret<FloatVar>()
                val rowGrades = grades?.let { (it + row.toLong() * gradeRowBytes)!!.reinterpret<UByteVar>() }
                var column = 0
                while (column < width) {
                    val metres = depths[column]
                    val graded = rowGrades == null || rowGrades[column] > WALL_LOW_CONFIDENCE
                    if (graded && metres.isFinite() && metres >= WALL_NEAREST_METRES && metres <= WALL_FURTHEST_METRES) {
                        val point = lens.pointAt(column, row, metres)
                        tiles.add(point.x, point.y, point.z)
                    }
                    column += WALL_PIXEL_STEP
                }
                row += WALL_PIXEL_STEP
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(map, kCVPixelBufferLock_ReadOnly)
            if (confidence != null) CVPixelBufferUnlockBaseAddress(confidence, kCVPixelBufferLock_ReadOnly)
        }
    }

    /** A plan of what has been drawn, the phone's track faint behind it, north up. */
    private fun drawPreview() {
        val strokes = mutableListOf<List<Coord2D>>()
        strokes.add(path.map { Coord2D(it.x, -it.y) })
        for (line in penLines) strokes.add(line.stroke.line().map { Coord2D(it.x, -it.y) })
        activeStroke?.let { stroke -> strokes.add(stroke.line().map { Coord2D(it.x, -it.y) }) }
        val fitted = ScanPreview.fit(strokes, WALL_PREVIEW_SIZE.toFloat(), WALL_PREVIEW_INSET.toFloat())
        val drawn = UIBezierPath()
        for (stroke in fitted.strokes) {
            val first = stroke.firstOrNull() ?: continue
            drawn.moveToPoint(CGPointMake(first.x.toDouble(), first.y.toDouble()))
            for (point in stroke.drop(1)) drawn.addLineToPoint(CGPointMake(point.x.toDouble(), point.y.toDouble()))
        }
        preview?.path = drawn.CGPath
    }

    private fun refreshLabels() {
        val station = stations.getOrNull(stationIndex)
        buttons[BUTTON_STATION]?.setTitle(station ?: NO_STATIONS, forState = UIControlStateNormal)
        buttons[BUTTON_KIND]?.setTitle(if (drawingWater) "Water" else "Wall", forState = UIControlStateNormal)
        buttons[BUTTON_SYMBOL]?.setTitle(symbolLabel(PALETTE[symbolIndex]), forState = UIControlStateNormal)
        buttons[BUTTON_DRAW]?.setTitle(
            if (activeStroke == null) "Hold to draw" else "Drawing...",
            forState = UIControlStateNormal,
        )
        sliderLabel?.text = "Smooth ${(smoothing * 100).roundToInt()}%"

        val marked = touches.map { it.stationName }.distinct()
        val lines = buildList {
            if (tracking.isNotEmpty()) add(tracking)
            add("${tiles.boxCount} boxes of rock")
            add(
                if (marked.isEmpty()) {
                    "No stations marked"
                } else {
                    "Marked: " + marked.joinToString(", ")
                } + " · ${penLines.size} lines · ${stamps.size} symbols",
            )
            add(aimingAt?.let { "Crosshair on rock ${(it * 10).roundToInt() / 10f} m away" } ?: "Crosshair: no rock in range")
            lastSaid?.let { add(it) }
        }
        status?.text = lines.joinToString("\n")
        view?.setNeedsLayout()
    }

    private fun say(message: String) {
        lastSaid = message
        haptics.prepare()
        haptics.impactOccurred()
        refreshLabels()
    }

    private fun currentFrame(): ARFrame? = arView?.session?.currentFrame

    @Suppress("unused")
    @ObjCAction
    fun noOp() = Unit

    @Suppress("unused")
    @ObjCAction
    fun previousStation() {
        if (stations.isEmpty()) return
        stationIndex = (stationIndex - 1 + stations.size) % stations.size
        refreshLabels()
    }

    @Suppress("unused")
    @ObjCAction
    fun nextStation() {
        if (stations.isEmpty()) return
        stationIndex = (stationIndex + 1) % stations.size
        refreshLabels()
    }

    @Suppress("unused")
    @ObjCAction
    fun aimStation() {
        val name = stations.getOrNull(stationIndex) ?: return
        val hit = currentFrame()?.let { centreHit(it) }
        GC.collect()
        if (hit == null) {
            say(NO_ROCK_UNDER_CROSSHAIR)
            return
        }
        touches.add(StationTouch(name, hit))
        say("Marked $name by aiming")
        nextStation()
    }

    @Suppress("unused")
    @ObjCAction
    fun touchStation() {
        val name = stations.getOrNull(stationIndex) ?: return
        val transform = currentFrame()?.camera?.transform?.wallFloats(DepthCamera.TRANSFORM_FLOATS)
        GC.collect()
        if (transform == null) return
        touches.add(StationTouch(name, touchPointInScan(transform, Coord3D.ORIGIN)))
        say("Marked $name by touch")
        nextStation()
    }

    @Suppress("unused")
    @ObjCAction
    fun penDown() {
        if (activeStroke != null) return
        val stroke = PenStroke()
        stroke.smoothing = smoothing
        activeStroke = stroke
        penTimer = NSTimer.scheduledTimerWithTimeInterval(interval = PEN_SAMPLE_SECONDS, repeats = true) { _ -> penSample() }
        refreshLabels()
    }

    @Suppress("unused")
    @ObjCAction
    fun penUp() {
        penTimer?.invalidate()
        penTimer = null
        val stroke = activeStroke ?: return
        activeStroke = null
        if (stroke.rawHits.size >= 2) {
            penLines.add(PenLine(stroke, penColour()))
            say("Line ${penLines.size} drawn")
        } else {
            say(NO_LINE)
        }
    }

    @Suppress("unused")
    @ObjCAction
    fun smoothingChanged() {
        smoothing = slider?.value ?: smoothing
        activeStroke?.smoothing = smoothing
        // The slider reaches the line being drawn, or the last one finished if none is.
        if (activeStroke == null) penLines.lastOrNull()?.stroke?.smoothing = smoothing
        refreshLabels()
        drawPreview()
    }

    @Suppress("unused")
    @ObjCAction
    fun toggleKind() {
        drawingWater = !drawingWater
        refreshLabels()
    }

    @Suppress("unused")
    @ObjCAction
    fun nextSymbol() {
        symbolIndex = (symbolIndex + 1) % PALETTE.size
        refreshLabels()
    }

    @Suppress("unused")
    @ObjCAction
    fun stamp() {
        val frame = currentFrame()
        val hit = frame?.let { centreHit(it) }
        val facing = frame?.camera?.transform?.wallFloats(DepthCamera.TRANSFORM_FLOATS)?.let { DepthCamera.bearingOf(it) }
        GC.collect()
        if (hit == null) {
            say(NO_ROCK_UNDER_CROSSHAIR)
            return
        }
        val symbol = PALETTE[symbolIndex]
        stamps.add(SymbolStamp(symbol, hit, facing ?: 0f))
        say("Stamped ${symbolLabel(symbol)}")
    }

    @Suppress("unused")
    @ObjCAction
    fun cancelScan() {
        if (finished) return
        finished = true
        stopEverything()
        runCatching { ScanRecovery.clear(surfaceStore()) }
        dismissViewControllerAnimated(true, null)
    }

    @Suppress("unused")
    @ObjCAction
    fun finish() {
        if (finished) return
        finished = true
        activeStroke?.let { stroke -> if (stroke.rawHits.size >= 2) penLines.add(PenLine(stroke, penColour())) }
        activeStroke = null
        stopEverything()
        val points = mutableListOf<Coord3D>()
        tiles.forEachPoint { points.add(it) }
        val scan =
            WallScan(
                points,
                path.toList(),
                touches.toList(),
                penLines.toList(),
                stamps.toList(),
                startStation,
                poorTracking.toSet(),
            )
        dismissViewControllerAnimated(true) {
            // Only once the scan is saved with the survey: if it could not be, the checkpoint is
            // what brings it back next time.
            if (onScanned(scan)) runCatching { ScanRecovery.clear(surfaceStore()) }
        }
    }

    private fun stopEverything() {
        sampleTimer?.invalidate()
        sampleTimer = null
        penTimer?.invalidate()
        penTimer = null
        arView?.session?.pause()
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        stopEverything()
    }
}

/** Where the app keeps its surveys, which is where an unfinished scan is kept too. */
private fun surfaceStore() = surveyFileStore

private fun distanceBetween(a: Coord3D, b: Coord3D): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}

private fun symbolLabel(symbol: Symbol): String = symbol.name.lowercase().replace('_', ' ')

/** Formations first: what a surveyor in Swildon's will reach for most. */
private val PALETTE =
    listOf(
        Symbol.STALACTITE,
        Symbol.STALAGMITE,
        Symbol.COLUMN,
        Symbol.CURTAIN,
        Symbol.STRAWS,
        Symbol.HELICTITES,
        Symbol.CRYSTALS,
        Symbol.GOUR,
        Symbol.WATER_FLOW,
        Symbol.AIR_DRAUGHT,
        Symbol.BLOCKS,
        Symbol.DEBRIS,
        Symbol.SAND,
        Symbol.CLAY,
        Symbol.PEBBLES,
        Symbol.GUANO,
        Symbol.GRADIENT,
        Symbol.TOO_TIGHT,
        Symbol.ENTRANCE,
    )

@OptIn(ExperimentalForeignApi::class)
private inline fun <reified T : CStructVar> CValue<T>.wallFloats(count: Int): FloatArray =
    useContents {
        val floats = ptr.reinterpret<FloatVar>()
        FloatArray(count) { floats[it] }
    }

private val WALL_GRAVITY_AND_HEADING = ARWorldAlignment.ARWorldAlignmentGravityAndHeading

private val WALL_FULL_SCREEN = UIModalPresentationFullScreen

private val WALL_DEPTH_SEMANTIC: ARFrameSemantics =
    when {
        ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSmoothedSceneDepth) ->
            ARFrameSemanticSmoothedSceneDepth
        ARWorldTrackingConfiguration.supportsFrameSemantics(ARFrameSemanticSceneDepth) ->
            ARFrameSemanticSceneDepth
        else -> ARFrameSemanticNone
    }

/** Five centimetres: fine enough to draw from, coarse enough for a long walk. */
private const val WALL_VOXEL_METRES = 0.05f

private const val WALL_PIXEL_STEP = 2
private const val WALL_NEAREST_METRES = 0.25f
private const val WALL_FURTHEST_METRES = 5f
private val WALL_LOW_CONFIDENCE: UByte = 0u

/** Two pixels either side of the centre: a five-by-five patch. */
private const val CENTRE_PATCH = 2
private const val CENTRE_MINIMUM = 5

private const val WALL_SAMPLE_SECONDS = 0.5

/** Ten samples at half a second: a checkpoint every five seconds. */
private const val WALL_CHECKPOINT_SAMPLES = 10
private const val PEN_SAMPLE_SECONDS = 0.1

private const val ROWS = 5
private const val ROW_HEIGHT = 48.0
private const val WALL_MARGIN = 16.0
private const val WALL_GAP = 8.0
private const val WALL_INSET = 8.0
/** The least room above the status panel and below the last row, where there is no safe area. */
private const val WALL_TOP = 24.0
private const val WALL_BOTTOM = 24.0
private const val WALL_PANEL_DIMNESS = 0.45
private const val WALL_BUTTON_DIMNESS = 0.6
private const val WALL_PANEL_CORNER = 10.0
private const val WALL_ROOM_FOR_ANY_TEXT = 10_000.0
private const val WALL_PREVIEW_SIZE = 120.0
private const val WALL_PREVIEW_INSET = 8.0
private const val WALL_PREVIEW_STROKE = 1.5
private const val SLIDER_LABEL_WIDTH = 110.0
private const val STATUS_FONT = 14.0
private const val BUTTON_FONT = 16.0
private const val CROSSHAIR_SIZE = 24.0
private const val CROSSHAIR_STROKE = 2.0

private const val BUTTON_PREVIOUS = "previous"
private const val BUTTON_STATION = "station"
private const val BUTTON_NEXT = "next"
private const val BUTTON_AIM = "aim"
private const val BUTTON_TOUCH = "touch"
private const val BUTTON_DRAW = "draw"
private const val BUTTON_KIND = "kind"
private const val BUTTON_SYMBOL = "symbol"
private const val BUTTON_STAMP = "stamp"
private const val BUTTON_CANCEL = "cancel"
private const val BUTTON_DONE = "done"

private const val START_SAYING = "Starting the camera"
private const val TRACKING_LIMITED = "Tracking limited: more light, slower"
private const val TRACKING_LOST = "Tracking lost: hold still and point at detailed rock"
private const val NO_STATIONS = "No stations"
private const val NO_ROCK_UNDER_CROSSHAIR = "No rock under the crosshair: move within 5 m"
private const val NO_LINE = "Too short to keep: hold Draw while sweeping"
