# SexyTopo on iOS — a Kotlin Multiplatform proof of concept

This directory is an **experiment**, not a product, and not a proposal to change the Android app
yet. It exists to answer one question with running code rather than argument:

> If SexyTopo's survey core were shared Kotlin instead of Android Java, would the same code
> actually drive an iOS app?

So far the answer is **yes for everything except the parts that need a Mac to check**. The survey
engine, the instrument protocols, the projection maths, the sketch model, the sketch *editor*, the
Survex and Therion exporters and the native file format are ported and covered by 733 shared tests,
each run on the JVM, on Kotlin/Wasm and on Kotlin/Native. The UI
is written once in Compose Multiplatform and renders through Skia, which is what Compose uses on
iOS — and it drives the ported logic rather than reimplementing it, which is the part that actually
tests the claim.

**Try it now:** <https://paperclipmonkey.github.io/sexytopo/> - the whole app compiled to
WebAssembly, including in Safari on an iPhone. That is the browser build rather than the
native one, but the survey core, the sketch engine and the Compose UI in it are the same code
the iOS build compiles.

Nothing in the existing Android app has been touched. `kmp/` is a separate Gradle build alongside
it; everything except the Android host builds without an Android SDK at all, which is the clearest
demonstration that the core no longer depends on one.

---

## What is actually proven, and what is not

Being precise about this matters more than the demo looking good.

| Claim | Status | Evidence |
| --- | --- | --- |
| Survey model, projection maths and the extended-elevation unroll port to Kotlin | **Verified** | tests, on two targets |
| The survey engine builds stations from readings the way the app does | **Verified** | `SurveyUpdaterTest` (59 tests), triple-shot promotion and all three amalgamation algorithms |
| Instrument packets decode identically | **Verified** | byte-level tests for DistoX, DistoX-BLE, BRIC, SAP6, Cavway, FCL |
| The whole chain works end to end | **Verified** | `SurveyingEndToEndTest`: simulated instrument → packet decode → station promotion → JSON round-trip |
| Native JSON survey/sketch formats read and written compatibly | **Verified, for three of the four files** | round-trip tests against Android-shaped fixtures, including corrupt and old-format files. The data file and both sketches are read and written; `Name.metadata.json` is neither, so the cross-survey links it carries do not survive a trip through this port — see *What to expect that is missing* |
| Survex and Therion export byte-identically | **Verified** | golden tests asserting the full file, metadata block included |
| **PocketTopo's own binary `.top` imports** | **Verified** | the format's primitives against the Android app's own `PocketTopoFileTest`, the shot-ordering and repeat-averaging rules against its `PocketTopoImporterTest` fixtures byte for byte, and its real `CeiledUp.top` — 12 stations, 68 legs, 203 strokes — read identically on the JVM, Kotlin/Wasm and Kotlin/Native, and through the file chooser in a browser |
| A PocketTopo text export imports, drawing included | **Verified** | the Android app's own `FAKE_TEXT` fixture and its three assertions, on three targets, plus the four files that crash the Java |
| **Every export is reproducible** | **Verified** | the same survey **built twice** exports byte-identically in all twelve format-and-projection combinations. Built twice and not merely exported twice: one set of objects has one identity-hash order, so a hash-ordered exporter agrees with itself and the weaker test passes on everything. The stronger one failed on its first run and found finding 45 — the `.xvi` exporter had the very defect this port reports in the Android app's |
| **No export throws on a survey that has barely started** | **Verified** | every format on five degenerate shapes — nothing in it, one station and no legs, one splay that never became a station, a leg with nothing drawn, a drawing with one station — asserting each produces a non-empty file. It matters because the export screen builds its file inside a `remember` block, so a throw there is a throw *inside a composition*, which on the web is finding 11: no error, no blank page, the last frame stays up and the app looks frozen. All twelve pass today; this is a guard, and it is cheap only because `exportText` was lifted out of the composable, where nothing could ask it anything |
| **And this app's own format exports the whole survey** | **Verified** | `NATIVE` wrote `Name.data.json` and nothing else, so exporting handed somebody a centreline and kept the drawing — the importer's loss at the other end, and the worse half of it: a reader's failure loses somebody else's work, a writer's loses your own. One press of *Save files* now writes the data file and both sketches, which is exactly what the folder import reads back. `ExportNamingTest` names all three; `field.mjs` presses the button once and counts what comes out of the browser |
| **A survey folder imports, not only a loose file** | **Verified** | `action_file_import_directory`. A survey arrives as a zip far more often than as a file, and unzipping one leaves a *folder* named after the cave — which the import list, looking only at files, could not see. Root directories that pass `SurveyStorage.isSurveyDirectory` are offered and loaded through the same loader the library uses, so all four files come in. The app's own `surveys/` is left out, being the library already |
| **A survey brought in keeps its drawing** | **Verified** | a SexyTopo survey is four files and this importer takes one at a time, so it read the centreline and dropped both sketches in silence. It now looks for `Name.plan.json` and `Name.ext-elevation.json` beside the file that was picked — where they land when somebody AirDrops a survey or unzips one into the Files app. Three unit tests and a browser check, and the browser fixture had to be **given a drawing**, because the one it had could not express the loss |
| A Survex or Therion file from other software imports | **Verified** | round-trip tests through the ported exporters, plus a `.svx` written by hand — team, date, backsights, splays, station comments and leg comments — and `field.mjs` brings one into the browser build end to end |
| The `.th2` and `.xvi` a Therion user actually needs come out of the app | **Verified** | golden tests on the scrap file and the tracing image, and `field.mjs` picks the `.th2` chip on a 420-pixel screen, saves the file and checks it has an encoding line, a named plan scrap and the `##XTHERION##` block that points it at the `.xvi` |
| Therion can build what comes out, rather than five files and a config to write | **Verified** | golden test on the `.thconfig` and on the `input` lines; `field.mjs` saves the project file and the `.th` from a phone-sized screen and checks each names what the other saves under |
| Compass `.dat` exports byte-identically | **Verified** | a golden captured by *running* the Android app's own exporter, not by reading it — which caught a transcription slip on the first attempt |
| PocketTopo `.txt` exports the same survey data | **Verified** | its DATA section is golden against the Android app; its station sections deliberately diverge, because the Java's are not reproducible even against themselves |
| **The view can follow the survey as it grows** | **Verified** | the preference round-trips with every other one, and `field.mjs` turns *Follow the survey* on, promotes a station from three readings, and finds the active station's amber brackets within forty pixels of the middle of the sketch — an assertion about where the view ended up, not merely that the screen changed, which it would have anyway |
| **A station can be found by name, and the last leg taken back** | **Verified** | `FindStationTest` — names and comments both searched, a station the survey no longer holds has no position rather than a crash, and the last leg is the last one *taken* rather than the last in any walk of the tree — and `field.mjs` finds a station on a phone screen and checks the view moved, then adds a splay, takes it back from the drawing menu and checks only it went |
| **The plan says which end of the survey you are working at** | **Verified** | `CentrelineDisplayTest` and `DashedLineTest` — the mark follows the last reading *taken*, splay included, as the Java's own paint order does; a leg is matched by identity, because two shots down a straight passage read the same; a pitch is out of the plan's plane and in the extended elevation's; and a leg too short to dash draws nothing rather than one stub that would read as solid — plus `field.mjs` finds the app's magenta on the drawn plan, turns the mark off and checks every magenta pixel went, fades the rest of the cave and checks the drawing got lighter, then brings it back |
| **It fits a small phone** | **Mostly** | `field.mjs` ends by resizing to 375x667 — an iPhone SE — and checking the toolbar is still where it computes it to be and the canvas still takes a stroke. It then opens the one dialog certain to overflow that screen and checks the mechanism the other dialogs rely on: the card is sized to the window rather than clipped, a wheel scrolls its text, and the button below the text is on screen and closes it |
| **And sideways, which is most of the keyboard case** | **Verified, for the half that is layout** | then 667x375 — the same phone turned over, and about the height a portrait phone has left once a keyboard has taken a third. The sketch still takes a stroke, and a dialog with a text field in it is measured to fit that height, typed into and confirmed from its own button. What this does **not** test is whether iOS reports the keyboard's height as a window inset at all; that is a device question. The vertical squeeze is the same problem either way, and it is now checked |
| **The table and the drawing are joined up, both ways** | **Verified** | `StationMenuTest` for each menu offering the views the other is not showing, `SurveyTableTest` for which row a station is found at and for which station a cell is about when the shot was booked backwards — and `field.mjs` taps both ends of one leg on a phone screen, checks they offer different menus, follows *show it on the plan* to a station that lands within forty pixels of the middle, then holds a station on the drawing and follows *show it in the table* back |
| **A reading can be corrected, annotated, reversed or unmade** | **Verified** | `LegActionsTest` and `SurveyUpdaterTest` — which actions each row offers and what they do: a leg with splays hanging off its far end is not offered the downgrade `SurveyUpdater` would throw over, the first reading of a survey is not offered a promotion there is no leg above for, a leg the survey no longer holds answers "no" instead of throwing, and a comment marks the survey unsaved, which the Android app's own dialogs do not — and `field.mjs` counts what the menu offers a splay and a leg on a phone screen, writes a note against a leg, checks the table gains the app's dagger, and turns the shot end for end and back again |
| **Any station can be reached from the sketch, not just the active one** | **Verified** | `StationMenuTest` for which actions a station offers — the origin has no incoming leg and no delete, cross-sections belong to the plan, a backsight is normalised the way the table normalises it — and `field.mjs` finds a station that is *not* the active one on the drawn plan, holds it, and checks that the menu moved the active station there without marking the paper |
| **The drawing can be moved without putting the pencil down** | **Verified** | `MultiTouchTest` for the pinch arithmetic and the corner geometry, and `field.mjs` finds the corner squares on the drawn page, drags one, and checks the plan moved, that no stroke was left behind, and that the next stroke still draws — with no toolbar round trip |
| A station being made can be felt rather than looked at | **Verified** | the callback fires once per station and not once per reading, the preference round-trips, and `field.mjs` turns it off through the settings screen and checks it stayed off |
| **The app can be told to stay dark, and remembers it** | **Verified** | `pref_theme` is a three-value list in the Android app — auto, light, dark — and this port had a session-only checkbox that started light on every run. In a cave that is the difference between a survey and fifteen minutes of no night vision. `AppPreferencesTest` covers the three values, the resolution against what the platform reports, and the round trip through the file; `field.mjs` sets Chromium to `prefers-color-scheme: dark` and watches *Automatic* follow it, then chooses **Dark** with the browser back on light, **reloads the page**, and checks the app comes back dark |
| **The mode the instrument is being held in is remembered** | **Verified** | the Android app reads `inputMode` out of `generalPrefs` on its way in; this port held it in a `var` that started at foresights every run, and the field bar only says anything when the mode is *not* foresights — so the state it came back in is the one that looks normal, and every leg after it is turned end for end with nothing in the numbers to show it. Now written down, along with the tool, the brush and the symbol, which `SketchPreferences` also keeps. `AppPreferencesTest` closes and reopens a `DemoState` over one store — the reading half as well as the writing half — and checks that a tool armed for a single touch is *not* restored; `field.mjs` taps the chips on a phone screen and watches the file follow, both ways round |
| **A lost instrument can be chased, and given up on** | **Verified** | a cave breaks Bluetooth constantly — the surveyor walks round a corner with the phone, the instrument sleeps, a cold battery sags — and every one of those cost a trip to the connection screen with cold hands. `ReconnectionPolicy` is ported from the Java with its scheduling taken out, so it can be driven by a clock a test controls: `ReconnectionPolicyTest` has the decisions, including that the window is measured from the *first* failure of a run so an instrument left at the last station stops costing battery on the way out. `ReconnectionTest` drives a fake radio through a drop, a recovery, an instrument that never comes back, and a second bad patch four hours later. `field.mjs` scrolls the settings dialog to the end, turns it on and reads the file |
| **The instrument's clock runs where the surveyor is** | **Verified** | it used to tick only while the connection or calibration dialog was open, so an attempt abandoned by closing the dialog never timed out and left the radio scanning, and a reconnection could never have happened at all — a surveyor waiting for an instrument to come back is drawing. One loop in `App`, keyed on the attached instrument, so it costs nothing on the demo cave. Finding 51 |
| **The drawing can be made big enough to read by head torch** | **Verified** | `preferences_sketching.xml`'s eight numbers — line widths, station size, the two font sizes, the symbol and text starting sizes — plus `pref_delete_path_fragments`, which decides whether the eraser takes the bit of a wall under your finger or the whole stroke. All nine were hard-coded here, and the eraser rule was worse than that: `SketchEditor.eraseAt` has taken the flag since the sketch was ported and nothing ever passed it. `SketchStyleTest` covers the file and the bounds; `DrawingSizeTest` renders the same survey at two leg widths through headless Skia and counts the red, because a number in a file is not a thicker line; `field.mjs` types 8 into the box on a phone screen and watches the plan go from 605 red pixels to 1852. Two upstream preferences that do nothing came out of reading this — finding 52 |
| **A bearing can be typed the way a compass reads it** | **Verified** | `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`. A DistoX reports a decimal and nobody needs this; a sighting compass is graduated in minutes and reads 123° 30′, and converting that in your head at every station is how a survey acquires arithmetic errors nobody can find afterwards — which matters here because this port already went out of its way to support a compass and tape and then asked for a decimal nobody's instrument shows. `DegreesMinutesSecondsTest` has the conversion both ways, the rounding carry, and the case upstream gets wrong; `field.mjs` turns both switches on, types 123 and 30 into the three boxes on a phone screen, flips the inclination's sign with the +/- button, and checks the survey stored 123.5 and **-5.5** — the direction as well as the size |
| **A packet the app cannot read costs a shot, not the trip** | **Verified** | every byte from a radio reaches one method, on the main thread, and none of it is under anybody's control — a truncated notification, a firmware revision with a field more, a device whose advertised name matched a profile it does not really speak. On iOS a Kotlin exception raised inside a CoreBluetooth callback ends the process, and an app that dies takes the connection, the screen and the surveyor's confidence with it. `InstrumentSessionTest` attaches a decoder that throws and checks the link stays up, nothing becomes a reading, and the log says a packet was dropped — run against the unguarded version, where it fails. Finding 56 |
| The instrument log is kept, persisted and readable on the phone | **Verified** | `ActivityLogTest` for the bounded queues and the file format; `instrument.mjs` connects a fake DistoX-BLE, takes a calibration, and then reads the log back off the clipboard — count, timestamps and all |
| The desktop build keeps its surveys too | **Verified** | a survey written by one `SurveyLibrary` and read by a second over the same directory, in SexyTopo's own file layout, plus the three platform conventions for where that directory goes |
| **A real-sized cave works, not just a demo one** | **Verified** | `BigSurveyTest` builds a four-thousand-station passage — past where every tree walk in this port used to overflow the stack — and projects it to a plan and an extended elevation, builds its wireframe, counts its statistics, exports it to Survex and Therion and reads it back, on all three targets, along with SVG, `.xvi`, `.th2`, Compass and PocketTopo — and rubs out and undoes on a drawing of eight thousand strokes |
| **A real-sized cave draws, and draws linearly** | **Verified** | `CanvasSpeedTest` renders the plan of a four-thousand-station survey through the same headless Skia the demo PNGs use, and checks that eight times the cave costs about eight times the frame rather than sixty-four — the failure mode finding 18 was, in the drawing rather than in the export. The absolute times are a CPU rasteriser's and not a phone's, and the test says so |
| **The cave is the same size on a phone as on a desktop** | **Verified** | `DrawingDensityTest` renders the same plan twice through headless Skia — once at 1x, once at three times the size *and* three times the density, which is what a phone shows — and compares what fraction of each picture is centreline. Dp sizes give one picture at two resolutions and the same fraction; raw pixels give a cave drawn a third as thick. Measured both ways: **1.09 as it stands, 0.44 with finding 28 put back**. It counts only the red centreline, because the text on that canvas is in `sp` and scaled correctly even when the bug was live — counting all the ink made the first version of this test pass with the bug in |
| **The manual is in the app** | **Verified** | the guide is bundled byte-for-byte from `app/src/main/assets/guide/index.html` and read into Compose by `parseManual`, with no web view on any platform. `ManualContentTest` asserts the bundled copy is identical to the Android app's, parses it, and counts the headings, paragraphs and list items **against the file's own tags** — the check that caught a nested list silently costing eleven items — plus every link pointing at a section that exists and every character being one the bundled font can draw. `field.mjs` opens it from Help, reads it, scrolls it, taps a contents row and closes it |
| **All four of the app's input modes are offered** | **Verified** | `SurveyUpdaterTest` has the engine half. `field.mjs` has the half only a running app can show: it switches to *Splays Only* and enters three readings agreeing within tolerance — the exact recipe for a station in every other mode — then checks that no station appeared and all three are still splays. Run first against a chip deliberately wired to `FORWARD`, where it fails |
| **Every character the app types is one the bundled font can draw** | **Verified** | `FontCoverageTest` asks Skia — the same `FontMgr` that does the drawing — for the glyph of every character the UI types, in both bundled weights, and fails on glyph 0. It asserts the other direction too: the two marks the app draws by hand, "✓" and "⋮", must stay absent, so a drawn mark that could be typed shows up as a failing test. The app bundles its own font because Skia ships none on the web, which is what makes one check answer for every platform |
| Surveys save and load through a platform-free storage layer | **Verified** | a full round trip - naming, directories, autosave, listing - over an in-memory `FileStore`, on all three targets. The Android app's equivalent test is `@Ignore`d because `DocumentFile` cannot be mocked |
| The sketch editor — tools, viewport, hit-testing, undo — is platform-free | **Verified** | `shared/sketch/`, driven by the demo and tested on two targets |
| The BLE connection logic is platform-free | **Verified** | `GattLinkTest` and `GattSessionTest` — the profile matrix *and* the connection lifecycle; only callback plumbing is left in `iosMain` |
| The DistoX calibration solver reproduces the Java exactly | **Verified** | the Android app's own two 56-shot datasets, asserting the *iteration counts* (43, 75, 53) as well as the errors — reproduced on the JVM, Kotlin/Wasm **and Kotlin/Native** |
| The 3D view's camera and projection port off OpenGL | **Verified** | `Matrix4Test` and `Camera3DTest` — the Android `Matrix` routines the renderer uses, and the camera on top of them, including that the whole cave is on screen when the view opens and fills it, on three shapes of screen; `field.mjs` opens it in the browser, counts what got drawn and turns it |
| Shared Compose UI draws, and can be drawn on | **Verified** | `./gradlew :demo:renderDemoPng`; drawing/erasing/undo covered by tests |
| **The shared core has no JVM-only dependencies** | **Verified** | every shared test passes on **Kotlin/Wasm** as well as the JVM |
| **The same code compiles for iOS** | **Verified** | `:shared:compileKotlinIosSimulatorArm64` in CI on a macOS runner — `iosMain`, `CoreBluetoothTransport` included |
| **The ported test suite passes on Kotlin/Native** | **Verified** | `:shared:iosSimulatorArm64Test` — the same tests as the JVM and Wasm jobs, on the actual target rather than a proxy for it |
| **The shared Compose UI links as an iOS framework** | **Verified** | `:demo:linkDebugFrameworkIosSimulatorArm64` — Compose's own Native klibs, the bundled font and the toolbar PNGs, resolved into the static framework `iosApp/` links against |
| **The same code compiles for a real iPhone** | **Verified** | `:shared:compileKotlinIosArm64` and `:demo:linkDebugFrameworkIosArm64` — a separate Kotlin/Native target from the simulator, with its own platform libraries, so a green simulator build does not imply it |
| **The manual is packaged, not just written** | **Verified** | `Res.readBytes("files/manual.html")` — the call the running app makes — in a JVM test **and** in the iOS simulator test, because packaging is per-target and neither answers for the other. This is the app's first `composeResources/files/` resource; the fonts prove the mechanism on iOS but `files/` is a different directory from `font/`, and a packaging mistake compiles, links, launches and draws a cave perfectly before failing on the one screen that needs it. Both tests parse what comes back and count the thirteen sections, so a truncated or re-encoded resource fails too |
| **The iOS half of the app runs, not just compiles** | **Verified** | `:demo:iosSimulatorArm64Test` on a macOS runner: the Documents file store round-trips text, non-ASCII, nested directories and a whole survey; a file's exact bytes come back through the hand-written `NSData` copy the PocketTopo reader needs; the log's timestamps come out in the Android app's own format; the clipboard and the new-station haptic do not bring the app down |
| **The iOS *app* builds, not only the framework it links** | **Verified** | `xcodegen` then `xcodebuild` for the simulator on the macOS runner, so `project.yml`, `Info.plist`, `Assets.xcassets` and the two Swift files are *compiled* rather than merely written — all four were authored on Linux and none had been near a compiler. It is also the only thing that runs `actool`, which is the only real answer to whether an app icon drawn here with PIL is one iOS accepts: an alpha channel or a wrong size is rejected outright, and nothing on Linux says so |
| **Compose actually draws the survey on iOS, and is still alive afterwards** | **Verified** | CI boots a simulator, installs the app, launches it and photographs it — then **looks again twenty seconds later**, and scans the host's crash reports for this bundle. The second look and the scan are new, and they exist because a phone found a crash this job could not: an app that launches, draws, passes the photograph and *then* dies is what an uncaught throw dispatched to a background queue looks like, and a check that looks once cannot see anything that happens afterwards. See finding 54. The picture has to contain the app's own panel green — an app that crashed shows Springboard, one with no UI shows white — *and* enough distinct colours to rule out the launch screen, which is that same green on purpose. Measured on the runner: **2340 green pixels and 609 distinct colours** against thresholds of 500 and 40. The screenshot is uploaded as the `ios-simulator-screenshot` artifact on every run |
| The iOS app runs on a device | **Not verified** | needs Xcode, an Apple developer account and a physical phone. The app *bundle* is now built by CI, so what is left is signing and installing it |
| **The app can ask to connect to an instrument** | **Verified** | `instrument.mjs` in CI stands a stub where `navigator.bluetooth` would be and makes it behave like a DistoX-BLE: the profile's name prefix and UUIDs reach the browser API, the notification arrives as a frame, the decoder reads it, the acknowledgement goes back, and three readings make a station in a saved survey |
| **A calibration can be taken, solved and written back** | **Verified** | `instrument.mjs` puts the fake DistoX-BLE into calibration mode, feeds it the Android app's own 56-shot dataset over Web Bluetooth, and checks the twelve coefficient blocks reach the device |
| `CoreBluetoothTransport` works | **Not verified** | it compiles, it now has a caller, and it has still never talked to a radio; the simulator has no Bluetooth stack, so this needs a real instrument |
| Web Bluetooth works against real hardware | **Not verified** | the chain is driven end to end against a fake instrument in CI; no real one has been near it |
| The whole app runs in a browser | **Verified** | a headless-Chromium smoke test in CI loads the page, draws a stroke and undoes it |
| The same UI builds and packages for **Android** | **Verified** | `:androidApp:assembleDebug` in CI; the APK is a build artifact |

**The honest summary:** the port compiles for iOS, its tests pass on Kotlin/Native, and the shared
Compose UI links as an iOS framework — all three checked on every push by a macOS runner, which
GitHub provides free on public repositories. The Mac that gated this project turned out to be a CI
job rather than a purchase.

What remains unverified is now much narrower, and all of it needs hardware rather than a toolchain:
the app running on a physical device, sketching latency under an Apple Pencil, and either transport
against a real instrument. The iOS *simulator* has no Bluetooth stack, so no amount of CI closes
that last one — but a stub standing where `navigator.bluetooth` goes does close everything either
side of the radio, from the profile's UUIDs to a station in a saved survey.

**"Expect to fix something on the first real build" was right**, and worth recording precisely,
because it is the calibration for everything else here. The first compile of `iosMain` found:

- two delegate properties whose anonymous types Kotlin refuses to infer;
- two pairs of Objective-C selectors that collapse onto one Kotlin signature and need
  `@ObjCSignatureOverride`;
- a missing `BetaInteropApi` opt-in, whose level is ERROR rather than warning.

It took three compile-fix cycles, because the conflicting-overload diagnostic *quotes the signature
the declaration collides with* rather than the one it is reporting — so the message names one
function while the line number points at the other, and reading the message gets you the wrong half
of the pair twice running.

---

## One UI, four platforms

`App()` is one composable, and it is a deliberate copy of SexyTopo's own sketch screen — the layout
of `activity_graph.xml`, the green panels from `colors.xml`, and the app's own toolbar artwork
carried across as Compose resources.

| At an iPhone 15 Pro's size | At a Pixel 8's | The same, dark |
| --- | --- | --- |
| ![iphone](docs/images/iphone-draw.png) | ![android](docs/images/android-plan.png) | ![dark](docs/images/android-dark.png) |

These are the shared composable rendered headlessly at each phone's dimensions, not photographs of
either phone — see the note under the gallery below. A genuine iOS screenshot, taken by CI from an
app running in a simulator, is attached to every run as `ios-simulator-screenshot`.

That copying is the point. A demo restyled to somebody's taste would prove that Compose can draw a
UI, which nobody doubts. A demo a SexyTopo user recognises on an iPhone — and can already use,
because the buttons are where their thumb expects — is an argument. So the centreline is red and
the splays are salmon, because that is what SexyTopo draws, not because it is what anybody would
choose from scratch.

![plan](docs/images/plan.png)

There is one layout rather than a phone one and a tablet one, because the app has one: nine
weighted columns spread out on a tablet and square up on a phone by themselves. Two buttons are
drawn but greyed — the symbol and select tools, which the shared model supports and this demo has
no palette or station menu to drive. Showing them disabled keeps the toolbar honest in both
directions: it is the app's toolbar, and what the demo cannot do is visible rather than quietly
missing.

## What the demo does

- **Two surveys.** A generated demo cave, and a **live survey** you build yourself.
- **Live surveying.** "Take reading" makes the simulated instrument emit a real DistoX wire-format
  packet; the ported protocol decodes it; the ported engine promotes three agreeing readings into a
  station. That is the core interaction of the whole app, and only the radio is pretend.
- **Sketching**, driven entirely by the shared `SketchEditor`, `SketchViewport` and `SketchTool`.
  Draw, erase and move tools, the Android toolbar's eight brush colours, undo/redo. Strokes are
  captured in survey metres (never pixels) and simplified on release. Erasing splits a stroke
  rather than deleting it — rubbing out the middle of a passage wall leaves both ends — and it
  hit-tests through the same visibility rule the renderer uses, so you cannot rub out what is too
  small to see.
- **Cross-sections**, drawn on the plan where the surveyor parked them.
- **Moving the drawing without changing tool.** A touch that starts in one of the four faint corner
  squares pans the sketch; two fingers zoom it, whatever tool is selected. Both are the Android
  app's, and the corner squares are its own — except that it tests four and tints three; see
  findings 22 and 23.
- **The cave in 3D**, turned with a finger. The Android app draws this with OpenGL ES; here the
  projection is arithmetic and the drawing is the same 2D canvas as everything else, so it runs on
  iOS, Android, the desktop and the web from one file.
- **The active station**, in the app's amber corner brackets, and the select tool that moves them.
- **Export** to Survex `.svx`, a Therion project — `.thconfig`, `.th`, and a `.th2` scrap and
  `.xvi` tracing image for each drawing — Compass `.dat`, PocketTopo `.txt`, SVG, and the app's own
  JSON, the same bytes the Android app would read back.
- **Import** of a Survex `.svx`, Therion `.th`, PocketTopo `.txt` or PocketTopo's own binary
  `.top`, as well as the app's own files: the club's existing survey of the cave, opened here to be
  extended. Both PocketTopo readers bring the *drawing* in as well as the centreline.
- **An instrument log you can read underground**, kept as it happens and copied off the phone with
  one tap — because a DistoX that will not pair does it in a cave, with no signal and no console.
- **A buzz when a station is made**, so the surveyor can look at the rock instead of the phone.
  Under *Surveying*, and on by default — which is what the Android app's own settings screen shows,
  though not what it does; see finding 21.
- **Plan and extended elevation**, the latter exercising the cave-unrolling maths.
- **The survey table**, with backwards shots normalised back to the reading as taken, a dagger
  against anything carrying a comment, and every row a way into the app's leg menu.
- Light and dark, and a layout that collapses to one scrollable toolbar on a phone.

| Extended elevation | Live survey from the instrument |
| --- | --- |
| ![elevation](docs/images/extended-elevation.png) | ![live](docs/images/live-survey.png) |

| Survey table | Export |
| --- | --- |
| ![table](docs/images/table.png) | ![export](docs/images/export.png) |

The 3D view, and the log that answers "why will this thing not connect" when there is no console
within a hundred metres of vertical rock:

| The cave in 3D, on a phone | What the instrument did |
| --- | --- |
| ![3d](docs/images/iphone-3d.png) | ![log](docs/images/instrument-log.png) |

Every image above except the log is rendered headlessly by `./gradlew :demo:renderDemoPng` — the
same composable the iPhone hosts, through the same Skia renderer, with no display attached. The log
is a real screenshot from the browser test, taken after it has driven a whole calibration through a
fake DistoX-BLE.

None of them is a photograph of an iPhone, and the ones labelled with a phone's name are that
phone's *dimensions* rather than that phone. There is a real one: CI installs the app on a booted
simulator, launches it and photographs it on every push, and attaches the result as
`ios-simulator-screenshot`. It is not checked in here because a screenshot committed by a build is
a file that goes stale the moment anything changes and that nobody re-checks — but it is one click
away on any green run, and a check on it (the app's own green, and enough distinct colours to prove
it is past the launch screen) is what makes the run green in the first place.

---

## Building

Everything below works on Linux/macOS/Windows except where noted.

```bash
cd kmp

./gradlew :shared:jvmTest          # the ported test suite
./gradlew :shared:wasmJsNodeTest   # the same tests on a NON-JVM target
./gradlew :demo:jvmTest            # the UI's use of the shared editor
./gradlew :demo:compileKotlinWasmJs      # the UI compiled for a non-JVM target
./gradlew :demo:renderDemoPng            # render the shared UI to PNGs, no display needed
./gradlew :demo:run                      # the desktop app (needs a display; keeps its surveys)
./gradlew :demo:wasmJsBrowserDistribution  # the browser build — see "The browser target" below
./gradlew :androidApp:assembleDebug      # the Android app (needs an Android SDK)
```

### Running on Android

Needs an Android SDK, which is the only thing this build wants that the others do not:

```bash
export ANDROID_HOME=/path/to/android-sdk
cd kmp && ./gradlew :androidApp:installDebug   # or assembleDebug for just the APK
```

The Android-specific surface is one activity that calls `setContent { App() }`, a manifest and a
theme. It installs with its own application id, so it sits on a phone next to the real SexyTopo
rather than replacing it — which is how you would want to compare them.

### Building for iOS (no Mac needed)

Checking that this *builds* for iOS needs nothing but a push. The `ios` job in
`.github/workflows/kmp.yaml` runs on a `macos-latest` runner — free on public repositories — and
does the four things that used to be unanswerable here:

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64        # iosMain, CoreBluetoothTransport included
./gradlew :shared:iosSimulatorArm64Test                 # the ported suite, on Kotlin/Native
./gradlew :demo:linkDebugFrameworkIosSimulatorArm64     # the framework iosApp/ links against
./gradlew :shared:compileKotlinIosArm64 \
          :demo:linkDebugFrameworkIosArm64              # the phone, which is not the simulator
```

Run those locally if you have a Mac; otherwise read them off the last CI run. Two of them are worth
a second look. `iosSimulatorArm64Test` is the same suite the JVM and Wasm jobs run, executing on the
actual target rather than on a target chosen because it also lacks `java.*`. And the last pair
exists because `iosArm64` and `iosSimulatorArm64` are separate Kotlin/Native targets with separate
platform libraries — device UIKit and CoreBluetooth are not the simulator's — so a green simulator
build is not evidence that the thing somebody carries underground compiles.

### Running on iOS (needs macOS + Xcode)

**Before any of this, try `./gradlew :demo:run`.** The desktop build is the same `App()` composable
the iPhone hosts, it needs no SDK and no simulator, and it now keeps its surveys between runs — so
it is the cheapest way to see whether the thing is worth putting on a phone at all.

Putting it on a simulator or a phone is where a Mac becomes unavoidable. The Xcode project is
**generated rather than committed**, because it was authored on Linux where a hand-written
`.pbxproj` cannot be opened or validated:

```bash
brew install xcodegen
cd kmp/iosApp && xcodegen && open iosApp.xcodeproj
```

The build runs `./gradlew :demo:embedAndSignAppleFrameworkForXcode` as a pre-build phase, which
compiles the Kotlin/Native framework and embeds it. `kmp/iosApp/project.yml` and the README section
below it describe a fully manual alternative if you would rather install nothing.

The iOS-specific surface is small and every file in it is one screen long:
`demo/src/iosMain/` holds twelve — `MainViewController.kt` is one function, and the rest are the
`actual` halves of things a phone has and a browser does not: the Documents file store, the
clipboard, the file picker, keeping the screen awake, the date and the timestamp, the haptic, the
export, the storage-durability answer and the instrument transports. `iosApp/` holds two Swift
files. `shared/src/iosMain/` holds one more, `CoreBluetoothTransport.kt`, for when you want real
instruments. Everything else — the whole survey engine, every importer and exporter, the sketch
editor, the calibration solver, the 3D camera and the entire user interface — is the same code the
Android and browser builds run.

#### Onto your own phone, step by step

Everything above is checked by CI. This part is not, because no CI runner has a phone plugged into
it — so it is written out in full, including the three places it is known to go wrong.

1. **Install the tools.** Xcode from the App Store — the whole thing, not just the Command Line
   Tools. Launch it once and let it install its additional components. Then:
   ```bash
   brew install xcodegen temurin@21
   # Kotlin/Native needs the full Xcode, not the Command Line Tools. If you have ever installed
   # the CLT on their own, xcode-select is probably still pointing at them, and the build fails
   # with "xcrun: error: unable to find utility" or a linker that cannot find the iOS SDK.
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   sudo xcodebuild -license accept
   ```
2. **Generate and open the project.**
   ```bash
   git clone https://github.com/paperclipmonkey/sexytopo.git
   cd sexytopo/kmp/iosApp && xcodegen && open iosApp.xcodeproj
   ```
   XcodeGen reads `project.yml` and writes `iosApp.xcodeproj` beside it. Open the **project**,
   not a workspace; there is no workspace.

   The build needs the network the first time: Gradle fetches Kotlin/Native's compiler and the
   Compose and Skia artifacts, which is a few hundred megabytes.
3. **Set a signing team.** Select the `iosApp` target → *Signing & Capabilities* → tick *Automatically
   manage signing* and choose your team. A free Apple ID works: add it under *Xcode → Settings →
   Accounts*, and it appears as *(Personal Team)*.
4. **Change the bundle identifier** to something nobody else has used — `org.hwyl.sexytopo.kmpdemo`
   is in this repository, so somebody may already have registered it. `uk.co.yourname.sexytopo` will
   do. Xcode will tell you, in red, if the one you picked is taken.
5. **Turn on Developer Mode on the phone.** iOS 16 and later hide it until you ask: *Settings →
   Privacy & Security → Developer Mode → on*, then restart the phone and confirm after it comes
   back. The toggle only appears once the phone has been plugged into a Mac running Xcode, so if it
   is not there, do step 6 first and come back. Without this the phone accepts the app and refuses
   to launch it, with a message about the developer being untrusted that looks like step 7's
   problem but is not.
6. **Plug the phone in** with a USB cable and unlock it. The first time, the phone asks whether to
   *Trust This Computer* — say yes. Pick it from the device menu at the top of the Xcode window
   (next to the scheme), and press ⌘R.

   The first build compiles Kotlin/Native and links Skia. Expect **five to fifteen minutes** on a
   modern Mac, with Xcode's progress bar apparently stuck on "Compile Kotlin Framework" for most of
   it — that is Gradle working, and the Xcode log pane (⌘9, then the build) shows what it is doing.
   Later builds are seconds unless Kotlin changes.

   The iPhone needs **iOS 15 or later**, which is anything from an iPhone 6s onwards.
7. **Trust the developer on the phone.** *Settings → General → VPN & Device Management → your Apple
   ID → Trust*. Until you do, the app installs and refuses to launch.

**If you would rather not install XcodeGen**, the same project can be made by hand. Everything below
is what `project.yml` says, typed into Xcode's own interface instead — which is also the honest
reason the spec is committed rather than the `.xcodeproj`: this list is short enough to check by
eye, a `.pbxproj` written on a machine with no Xcode is not.

1. *File → New → Project → iOS → App*. Interface **SwiftUI**, Language **Swift**. Save it
   **outside** the repository: a new project saved on top of `kmp/iosApp` collides with the files
   already there.
2. Delete the `ContentView.swift` and `…App.swift` that Xcode generated. Then *File → Add Files*,
   **untick** *Copy items if needed*, and add all four of `kmp/iosApp/iosApp/iOSApp.swift`,
   `ContentView.swift`, `Info.plist` and `Assets.xcassets`.
3. In the target's *Build Settings*, set: **Info.plist File** to the one you just added and
   **Generate Info.plist File** to *No*; **Primary App Icon Set Name** to `AppIcon`; **User Script
   Sandboxing** to *No*; **Framework Search Paths** to the absolute path of
   `kmp/demo/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`; **Other Linker Flags** to
   `-framework SexyTopoDemo`; and the deployment target to **iOS 15**.
4. In *Build Phases*, add a **Run Script** phase, drag it **above** *Compile Sources*, untick *Based
   on dependency analysis*, and give it:
   ```bash
   cd /absolute/path/to/sexytopo/kmp
   if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
     JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null)"
     export JAVA_HOME
   fi
   ./gradlew :demo:embedAndSignAppleFrameworkForXcode
   ```

Then carry on from step 3 above. This path has not been run here either — there is no Xcode on the
machine this was written on — but every value in it is copied from the spec XcodeGen would have
used, and the whole point of listing them is that you can compare the two.

Four things go wrong, in roughly this order of likelihood:

- **The app builds, runs, and then dies a moment later — often on the first thing you tap, which
  makes it look as though a button is broken.** Compose Multiplatform's own
  `androidx.compose.ui.uikit.PlistSanityCheck` calls `error()` unless `Info.plist` has
  `CADisableMinimumFrameDurationOnPhone` set to `<true/>`. The committed plist has it; a
  hand-built project with *Generate Info.plist File* left on will not, and the key is not one
  Apple's templates add. It is checked on a low-priority background queue, so the throw lands
  whenever that queue next gets CPU rather than at launch — which is why it reads as a crash in
  whatever you happened to be doing. See finding 54.
- **"Unable to locate a Java Runtime"** in the build log. Xcode runs script phases with a stripped
  environment and no login shell, so a JDK installed by Homebrew or SDKMAN is not on `PATH`. The
  pre-build script already asks `/usr/libexec/java_home` for one; if you installed a JDK somewhere
  that does not answer to it, set `JAVA_HOME` explicitly in that script phase.
- **"Sandbox: ... deny file-write"**. `ENABLE_USER_SCRIPT_SANDBOXING` is set to `NO` in
  `project.yml`, which is what allows Gradle to run at all. If you built the project by hand rather
  than with XcodeGen, set it yourself in *Build Settings*.
- **The app expires after seven days.** That is a free Apple ID, not a bug. Re-run ⌘R to reinstall,
  or use a paid developer account, which lasts a year. Worth knowing before a weekend underground:
  build it the day before, not a week before.
- **`xcrun: error: unable to find utility`, or a linker that cannot find the iOS SDK.** `xcode-select`
  is pointing at the Command Line Tools rather than at Xcode; see step 1.

One cosmetic thing, and one honest caveat about it:

- **The icon and the launch screen compile, and have still never been *looked at*.**
  `iosApp/iosApp/Assets.xcassets` holds a 1024×1024 icon — a cave centreline drawn on a pale panel, in the
  app's own colours — and a colour called `LaunchBackground` that `UILaunchScreen` names, so the
  first moment shows the panel green rather than white. Both were written on Linux, where there is
  no Xcode to compile an asset catalogue.

  CI now builds the whole Xcode project on a macOS runner, so `actool` has compiled the catalogue
  and accepted it: the icon is not rejected for an alpha channel or a wrong size, and the colour
  set parses. `IosAssetsTest` checks the same rules here in a second, so a later edit that breaks
  one fails on any machine rather than six minutes into somebody else's build.

  What nobody has done is *look* at either. A build that succeeds says the icon is well-formed, not
  that it is any good at 60 pixels on a home screen. If you dislike it, delete
  `iosApp/iosApp/Assets.xcassets` and rebuild: nothing else refers to it.

#### Before you demo it: what has and has not been run

Be precise about this, because it is the difference between a demo that surprises you and one that
does not.

**Checked on every push, on a macOS runner:** the shared code compiles for the phone (`iosArm64`,
a different target from the simulator with its own platform libraries); the whole ported test suite
passes on Kotlin/Native; the Compose UI links as an iOS framework; the iOS half of the app —
`DocumentsFileStore`, a survey saved and reopened, a file's exact bytes through the `NSData` copy
the PocketTopo reader needs, the date, the log's timestamps, the clipboard and the new-station
haptic — *runs* in a simulator; the Xcode project builds, `actool` and all; and then the app is
installed on a booted simulator, launched, and **photographed**. The screenshot is attached to
every run as `ios-simulator-screenshot`, and a check on it is what makes it evidence rather than
decoration: it has to hold the app's own panel green *and* enough distinct colours to prove the
launch screen is not all that is on the glass. The last run: 2340 green pixels, 609 colours.

**Never run on a device.** A simulator is a different thing from a phone: no touchscreen, no
Bluetooth, no Apple Pencil, and an Apple Silicon Mac's own GPU rather than a phone's. Nobody has
pressed ⌘R with a cable attached before you. What that leaves genuinely unknown is the *feel* —
latency under a finger, the two-finger gestures, whether a stroke keeps up — rather than whether it
comes up at all, which is now a picture rather than a hope.

**Never near a radio:** `CoreBluetoothTransport`. The simulator has no Bluetooth stack, so CI
cannot exercise it, and no instrument has been near a phone running this. *Instrument* will ask for
Bluetooth permission and start scanning; whether a DistoX-BLE actually appears is genuinely unknown.
Everything above the radio — the profiles, the decoders, the acknowledgement handshake, the
calibration — is driven end to end against a fake instrument in CI, so if the radio works the rest
should follow.

**Unmeasured:** sketching latency under a finger or an Apple Pencil, on a phone. The engine
underneath it *has* been measured, at sizes a real cave reaches: with eight thousand strokes on the
drawing, a tap costs about two milliseconds to resolve and an erase about the same, both linear in
the number of strokes. What is left unknown is the rendering, which is Skia's and not this port's.

#### What you can do on the phone

Everything in this list is walked end to end by `field.mjs` on a 420-pixel screen on every push,
and the iOS file handling underneath it runs in a simulator on the macOS runner:

- **Record a trip.** Name a survey, type readings off the instrument's display, and watch three
  that agree promote to a station under the app's own tolerance rules. All four of
  `action_bar.xml`'s modes are here — *Forward*, *Backsight*, *Fore + back* and *Splays Only*, the
  last of which stops anything promoting at all, for a run of splays round a chamber — and they
  mean what they mean in the app, with the field bar saying which is on.
- **Fix a mistake.** Tap a table row to correct a reading, delete it, or promote a splay to a
  station. A correction keeps the destination station, so it cannot silently take the rest of the
  cave with it.
- **Name the junction, and measure the passage.** Stations take a name, a comment and the
  extended-elevation direction that decides which way a branch unrolls — and four tape
  measurements, left, right, up and down, which become ordinary splays. That is how a survey is
  booked when there is no instrument in the party, and it is what lets a cross-section be drawn
  from a hand-booked survey.
- **Know which way is north.** The plan carries the app's own north arrow, above the scale bar, and
  *Show north* takes it off again for anyone who wants the corner of the screen back. It had one in
  the exported SVG and not on the screen, which is the kind of gap no test finds because nothing is
  *wrong* — something is simply absent. It does not yet swing with the phone; on a plan north is
  up, so it is right rather than approximate, and what is missing is the sensor.
- **Find the switch you are after.** The drawing menu is the app's own, and this port reaches more
  from it than the app does — a nine-column toolbar has no room left — so at eighteen rows it was
  taller than an iPhone SE. It is now what `drawing.xml` always said it was: seven things that
  *do* something on the menu, and the twelve toggles in a dialog under that file's own two group
  names, *Shown* and *Behaviour*. The dialog applies as you tick, because "show grid" is a question
  you answer by looking, and it stays open, because nobody changes only one.

  The overflow menu went the same way an hour later, and for the same reason: flat, it was fourteen
  rows plus one per saved survey, and on an SE the last of them was drawn half off the bottom edge.
  It is `action_bar.xml`'s own five now — File, View, Instrument, Settings, About — with the saved
  surveys inside File where the app's own *Open* is.
- **Give the drawing the whole screen.** *Full screen*, in Settings, takes the app bar away and
  leaves a slim handle in its place. It matters most turned sideways, which is how a wide passage
  gets drawn: on a phone in landscape the app's own chrome is about half the height, and the app
  bar is the part somebody mid-stroke has no use for. The handle is there because hiding the app
  bar hides the only route back to the menu that turned it on — `action_fullscreen`, off by
  default as `isImmersiveModeOn` is.
- **Take the clutter off.** *Show cross-sections* hides them when the plan is busy — and stops
  them being tapped while hidden, which is the app's own rule and the half a port forgets. *Pinch
  to zoom* turns the two-fingered zoom off for anyone drawing with a stylus, where a second contact
  is usually the side of a hand; one preference over the drawing, a cross-section and the 3D view,
  as it is in the app.
- **Sketch it**, in plan and extended elevation, each with its own strokes and its own undo
  history — write on it (*sump*, *boulder choke*, *continues*), stamp any of the nineteen UIS
  symbols, and drop a cross-section at a station, drawn from that station's own splays.
- **Overrule the app.** A cross-section's bearing is a guess and its position is wherever your
  finger landed. *Re-aim a cross-section* swings it round its station until it cuts the passage
  square; *Move a cross-section* slides it, drawing and all, off the centreline it is sitting on.
  Both preview under the finger, and what is previewed is what is committed.
- **Join the wall up.** *Snap to lines*, in the drawing menu, makes a new stroke start and finish
  exactly on the end of a nearby one. A passage wall is drawn as a series of strokes, and a wall
  with gaps in it is one no tracing tool downstream can close. Off by default, as in the app.
- **Let the view follow you.** *Follow the survey* puts the active station back in the middle each
  time a new one is made, at the zoom you chose — `buttonAutoRecentre`, and off by default as it is
  in the app. Worth knowing why it is worth turning on: without it the view re-fits the *whole cave*
  as the survey grows, so by the fiftieth station the working end is a few pixels across and you are
  pinching in after every leg.
- **Find a station.** *Find a station…* searches names **and comments** — stations are called 1, 2,
  3, and what a surveyor remembers is "the one where the draught was" — and takes the view to the
  one you pick without changing the zoom you chose. A survey of any size does not fit on a phone
  screen, and pinching out until the whole cave fits is exactly the zoom at which the labels are too
  small to read.
- **Take back the last leg.** *Delete the last leg* removes the shot just recorded, named so you can
  see it is the one you mean. The Android app does this without asking; this port asks, because the
  sketch has an undo stack and the *survey* does not, in either app — and on the Android drawing
  menu it sits one row from a display toggle.
- **See which end of the survey you are working at.** On a plan that has grown past a screenful,
  every red line looks like every other one. Three of the app's answers are here: the leg just
  taken is drawn in its magenta; *Fade all but the working end* drops everything that does not hang
  off the working station to a fifth alpha, without moving the view; and a leg that does not lie in
  the plane being drawn — a pitch on a plan — is dashed rather than left as a stub indistinguishable
  from a short crawl. A stamped stream comes out blue whatever the brush is set to, which is the
  app's own rule and the convention of every published cave survey.
- **Go between the table and the drawing, either way.** A tap on a station's name in the table
  opens *that station's* menu rather than the leg's — the Android app's
  `table_station_selected.xml`, which differs from the sketch's in exactly two ways: cross-sections
  are a thing you draw, so only the sketch's menu offers them, and `menu_navigate` offers the two
  views you are *not* looking at. From the table that is the plan and the elevation; from the
  drawing it is the table, which lands on the leg that arrived at the station — the row with its
  own reading on it and its splays underneath.

  Both directions matter and they answer opposite questions. Scan the table, spot the reading that
  looks wrong, tap the station, see where it actually is. Or look at the plan, see a passage
  heading somewhere it should not, hold the station and read the numbers that put it there. Which
  end of a leg a cell is about depends on whether the shot was booked backwards, and the port asks
  the same question of the column that `TableActivity` does.
- **Correct, annotate or unmake a reading.** Every row of the table, and the incoming leg on any
  station's menu, offers what the Android app's leg menu offers: edit the numbers, write a comment
  on it, reverse a shot booked the wrong way round, make a splay into a station or fold it into the
  leg above, take a station back down to the splay it came from, or delete it. Three of those —
  reverse, downgrade and promote — had been in the ported engine since the beginning with nothing
  in the app able to ask for them. A leg or station carrying a comment is marked in the table with
  the app's own dagger, and the comment comes out in the Survex and Therion files, so a note made
  underground reaches whoever draws the cave up.
- **Get at any station, not just the active one.** Hold a station on the sketch and its menu comes
  up, whatever tool is in hand: start the next leg here, name and comment and measure it, draw or
  open or delete its cross-section, edit or reverse or delete the leg that got here, or delete the
  station and the passage beyond it. Before this the only station a surveyor could name was the
  *active* one, through the chip on the field bar — fine while the survey is being pushed forward,
  and useless the moment somebody wants to go back and write "sump" on a junction they passed
  twenty minutes ago.
- **Move the drawing without putting the pencil down.** A touch that starts in any of the four
  faint corner squares pans the sketch instead of marking it, and two fingers zoom it, whatever
  tool is selected — the app's own hot corners and its `ScaleGestureDetector`, which this port had
  been missing. Moving the paper is the most frequent thing anybody does to a sketch, and doing it
  through the toolbar costs two taps each time. *Surveying* turns the corners off, and turns on the
  app's other escape, a two-fingered drag; the defaults are the Android app's, corners on and two
  fingers off.
- **Calibrate the instrument.** *Calibrate* runs the app's own procedure: fourteen directions,
  each rolled through four positions, fifty-six shots in all, then Beat Heeb's solver and the
  coefficients written back to the device. An uncalibrated DistoX can be several degrees out, and a
  survey is a chain of bearings, so the error accumulates along the passage — the cave comes back
  the wrong shape and nothing in the numbers says so. The run is saved as it is taken, in the
  Android app's own JSON format, so a flat battery twenty minutes into fifty-six shots does not
  mean starting again. Without an instrument the simulated one replays a real 56-shot calibration,
  so the whole chain can be seen working.
- **Draw the passage.** Tap a cross-section and it opens into its own screen — the same canvas,
  tools, viewport and undo stack as the plan, over the section's own world: the station at the
  origin and its splays around it. A star of splays is not a passage; the outline drawn round it,
  joining the wall shots and closing the gaps where nobody took one, is what makes it one. *Cancel*
  really does discard, because the editing happens in a copy.
- **Read who wrote it.** *About* carries the Android app's own text: Rich Smith and the eight
  named contributors, the people thanked — Beat Heeb among them, whose calibration solver is ported
  line for line — where to get the real app, and the GPL-3.0 notice. This port had none of it,
  which meant it was distributing several thousand lines of somebody else's GPL code with the
  authors nowhere a user could see them, and the GPL asks an interactive program to show its legal
  notices. A last paragraph is this port's own and says what this build is not: not the app from
  the Play Store, not supported by its author, and never connected to an instrument.
- **Say who was there.** Trip details records the date, the team and their roles, the instrument,
  and the copyright and licence terms, and every exporter writes them.
- **Match the tolerances to the instrument.** The defaults assume a DistoX; a compass and tape
  needs looser ones, and without that nothing ever promotes to a station. *Surveying* sets them,
  and they persist.
- **Try an instrument.** *Instrument* offers the seven BLE families the port carries profiles for.
  On iOS that is CoreBluetooth; in Chrome, including on Android, it is Web Bluetooth. Neither has
  met real hardware — see the table above — but the acknowledgement handshake four of these
  instruments need is implemented and tested, which is the part that fails silently.
- **Take it home.** Survex, Compass, PocketTopo, or the native format — which is the *whole*
  survey, data file and both sketches, so what comes out is what the import at the other end
  reads back — or a Therion *project* rather
  than a pile of files: the `.thconfig` Therion actually compiles, the `.th` centreline naming both
  scraps, and a `.th2` and an `.xvi` for each drawing, each named after the drawing it holds so the
  plan and the elevation are not the same file. Nothing to write by hand between saving them and
  running `therion` — or the drawing itself as SVG, which is the whole plan or extended elevation with its passage walls, centreline, splays,
  station labels, symbols and cross-sections, openable in Inkscape or any browser. The SVG carries
  a legend below the drawing: title, date, who was there, surveyed length and vertical range, the
  copyright line, a scale bar and — in plan, where it means something — a north arrow. A drawing
  without those is a picture rather than a survey, and no club will take it. Dated from the phone's
  own clock, to the clipboard or to a real file with the right extension. On iOS the files land in
  the Files app under *On My iPhone → SexyTopo KMP*, because `UIFileSharingEnabled` is set.
- **Bring one back in.** Put a `.data.json` in the app's folder — on iOS that is Files, under *On
  My iPhone* — and *Import* offers it. It never overwrites a survey already in the library, which
  matters when a colleague sends you their copy of a cave you are also surveying.
- **See how big it is.** *Statistics* is the app's own panel — length, depth, stations, legs,
  splays, shortest and longest shot — which is what a surveyor asks for underground rather than
  afterwards.
- **Lose nothing.** Every change is written immediately, the survey is there after a restart, and
  the app opens with no signal at all. In a browser the app also asks for persistent storage on
  startup, because `localStorage` is otherwise storage the browser may reclaim; if it is refused,
  the export screen says so rather than letting you assume the trip is safe.

#### On a smaller phone

The screenshots in this README are from a 420x900 screen. An iPhone SE is 375x667, and three
things in this app went past that limit. The drawing menu reached eighteen rows — 864 pixels of
popup, on a 667-pixel screen — and has been split: the seven items that *do* something stay on the
menu, and the twelve toggles moved into a dialog of their own under `drawing.xml`'s own two group
names. The overflow menu went the same way and for the same reason, back to `action_bar.xml`'s own
File / View / Instrument / Settings / About, with the saved surveys inside File; flat, it was
fourteen rows before a single survey was saved, and the last of them was drawn half off the bottom
of the screen. And the station dialog — a name, a comment, four passage measurements and the elevation direction — is
most of a screen before a keyboard takes a third of what is left. Material 3 scrolls a dropdown
that does not fit and *clips* a dialog that does not, so the three dialogs with several fields in
them were made scrollable: the station dialog, the reading dialog and the edit-reading dialog.

Say plainly what that is worth. `field.mjs` finishes at 375x667: it checks the app still draws and
still takes a stroke there, and then puts up the one dialog guaranteed to overflow that screen —
the About box, a screenful and a half of text — and checks the *mechanism*. The card is sized to
the window rather than running off the bottom of it; a wheel over the text moves the text; and the
button below the text is still on screen and still closes the dialog. That is the half that was
reasoned rather than run, and it is run now.

The keyboard itself is still not run — a headless browser has not got one — but half of that case
is now covered, and it is the half that fails silently. A keyboard's effect on layout is that the
window gets shorter, and an iPhone SE turned over is 667x375: about what a portrait phone has left
once a third of it is keypad. So `field.mjs` finishes there too, draws a stroke, and opens a dialog
*with a text field in it* — measuring that the card fits those 375 pixels, then typing a name into
it and confirming from its own button.

What that does not answer is whether iOS reports the keyboard's height as a window inset in the
first place, which is plumbing rather than layout and which only a device can settle. Said plainly
because a check that covers half a case is worse than none if it is read as covering all of it. If
a dialog does come up short on your phone, the fix is one modifier and the four that have it show
where it goes.

#### What to expect that is missing

Honest limits, so nothing is a surprise in a cave:

- **No instrument has been near it.** The app can now ask to connect — *Instrument* lists the
  device families, and iOS uses CoreBluetooth while Chrome uses Web Bluetooth — and the whole chain
  from profile to saved station is driven against a *fake* instrument in CI. No real one has been
  tried on either platform, and the iOS simulator has no Bluetooth stack, so it cannot be. Expect to
  type readings, which is what *Add reading* is for and which behaves identically.
- **A cross-section holds only lines.** It can be placed, re-aimed, moved and drawn into, but its
  own editor keeps paths and drops symbols and labels on commit — which is what the Android app's
  editor does too, so this matches rather than diverges.
- **Browser storage can still be reclaimed.** The build asks for persistent storage at startup, but
  a browser may refuse — Chrome grants it silently only to a site you have engaged with or
  installed to the home screen. When it refuses, the export screen says so. On iOS this does not
  arise: files in the app's own container stay there.
- **The original DistoX and DistoX2 will never work here.** They speak Bluetooth Classic RFCOMM,
  which iOS has no public API for and no browser implements. That is permanent, and not a gap this
  project can close.
- **This is a port, not the app.** Use it beside a notebook, not instead of one.

---

## How it maps to the Android app

| Android app (Java) | Here (Kotlin) | Notes |
| --- | --- | --- |
| `model/graph/*`, `model/survey/*` | `shared/model/` | Straight translation |
| `model/graph/Projection2D` | `shared/model/graph/Projection2D.kt` | Including the load-bearing y-flip |
| `control/util/Space{2,3}DUtils`, `Space3DTransformer(ForElevation)` | `shared/math/` | The extended-elevation unroll |
| `control/util/SurveyUpdater`, `amalgamation/*`, `StationNamer` | `shared/survey/` | Triple-shot promotion, all three amalgamation algorithms, real default tolerances |
| `model/sketch/*` | `shared/model/sketch/` | Needed no translation — see below |
| `model/sketch/Symbol` + 19 vector drawables | `shared/model/sketch/Symbol.kt` | **Generated** from the drawables; artwork drawn by `math/SvgPath.kt`, arcs included |
| `io/thirdparty/therion/Th2Exporter` | `shared/io/export/Th2.kt` | The scrap file, its cross-section anchors and the `##XTHERION##` block |
| `io/thirdparty/xvi/*` | `shared/io/export/Xvi.kt`, `XviGlyphs.kt`, `XviSymbolPaths.kt` | The glyph font and the symbol polylines are **generated** from the Java, as `Symbol` and `Colour` were |
| `io/thirdparty/svg/SvgExporter` | `shared/io/export/Svg.kt`, `SvgLegend.kt` | Deterministic where the Java's `HashMap` order is not; legend laid out with the Java's own arithmetic, ISO dates instead of a locale's |
| `control/util/SurveyStats`, `StatsActivity` | `shared/survey/SurveyStats.kt`, `demo/.../StatsDialog.kt` | Including the two pieces of arithmetic that look like mistakes |
| `model/sketch/Colour` (150 values) | `shared/model/sketch/Colour.kt` | **Generated** from the Java enum so values cannot drift |
| `comms/distox/*`, `distoxble/`, `bric4/`, `cavwayx1/`, `sap6/`, `fcl/` | `shared/comms/` | Protocol only, no transport |
| `control/calibration/*`, `model/calibration/*` | `shared/calibration/` | Beat Heeb's solver; results returned rather than left in statics |
| `DistoXCalibrationActivity` | `shared/calibration/CalibrationRun.kt`, `demo/.../CalibrationDialog.kt` | The 56 positions, the assessment, and the write-back |
| the `*Manager` classes' device knowledge | `shared/comms/InstrumentProfile.kt` | The BLE device matrix, as data |
| Nordic `BleManager` subclasses | `shared/iosMain/.../CoreBluetoothTransport.kt` | The whole iOS Bluetooth surface |
| `control/io/basic/*JsonTranslater` | `shared/io/` | Same tags, same tolerant two-pass load; the calibration file is interchangeable both ways |
| `control/graph/GraphView` — tools, viewport, hit-testing, snap-to-lines | `shared/sketch/` | Ported; the demo drives it |
| `GraphActivity.handleAutoRecentre`, `SketchPreferences.Toggle.AUTO_RECENTRE` | `demo/.../App.kt`, `CanvasController.centreOn` | Driven by a counter the canvas watches, because the viewport belongs to the canvas and the station-created event does not |
| `action_find_station`, `StationSelectorDialog`, `buttonDeleteLastLeg` | `demo/.../FindStation.kt` | The list is shown rather than autocompleted, and comments are searched as well as names |
| `GraphView.drawLegs`, `drawStations`, `drawDashedLine`, `isAttachedToActive` | `shared/sketch/DashedLine.kt`, `demo/.../SurveyCanvas.kt` (`SceneSegment`) | The facts about a leg are settled when the survey is projected, not inside the draw loop |
| `control/util/CohenSutherlandAlgorithm`, `GraphView.isLineOnCanvas` | `shared/sketch/Clipping.kt`, `demo/.../SurveyCanvas.kt` | The half of the algorithm the Java uses — the test, not the clip; and the same test extended to stations, which the Java does not cull |
| `GraphView.dpToPixels` on every drawn size | `demo/.../SurveyCanvas.kt` (`CanvasSizes`), `ThreeDView.kt` | Finding 28: a plain number in a `DrawScope` is a physical pixel, so the whole drawing was a third of its size on a phone |
| `Sketch.addSymbolDetail`'s blue-water override | `shared/sketch/SymbolColour.kt` | A rule about a preference, kept out of the generated `Symbol` enum |
| `res/menu/context_leg.xml`, `ContextMenuManager.configureMenuVisibility`, `SurveyEditorActivity`'s leg handlers | `demo/.../LegActions.kt`, `SurveyUpdater.can{Downgrade,PromoteToAbove}Leg` | An action that cannot work is left out rather than shown greyed out or answered with a toast |
| `res/menu/table_station_selected.xml`, `TableActivity.onCellClicked` | `demo/.../SurveyTableView.kt`, `StationMenu.kt` (`fromTable`) | One dialog reached two ways; the jump is left as a request for the sketch to pick up, because the viewport belongs to a canvas that does not exist yet |
| `menu_navigate` (`action_jump_to_table`) | `demo/.../SurveyTableView.kt` (`rowIndexFor`), `DemoState.showInTable` | The row a station is found at is the leg that arrived at it, which is also the row its splays sit under |
| `values/about_text.xml`, `openAboutDialog` | `demo/.../AboutDialog.kt` | Verbatim but for the bullet character, plus a paragraph saying what this build is and is not |
| `TableRowAdapter`'s `COMMENT_MARKER` | `demo/.../SurveyTableView.kt` | The dagger goes on the station the row *shows*, which for a backsight is not the one the leg starts at |
| `res/menu/context_station.xml`, `ContextMenuManager`, `GraphView.LongPressListener` | `demo/.../StationMenu.kt`, `SurveyCanvas.detectLongPress` | A dialog rather than a menu anchored at the finger; the links submenu is out, since nothing here draws a neighbouring survey |
| `GraphView.isModalMoveSelection`, `didEventHitHotCorner`, `ScaleListener` | `shared/sketch/MultiTouch.kt`, `SketchViewport.kt`, `demo/.../SurveyCanvas.kt` | Pan and zoom without leaving the tool; the fourth hot corner is drawn as well as tested |
| `GraphView.handle{Move,Rotate}CrossSection` | `demo/.../CrossSectionDrag.kt` | One value drives the preview *and* the commit, so they cannot disagree |
| `CrossSectionActivity`, `CrossSectionView` | `demo/.../CrossSectionEditor.kt` | The same canvas over the section's own world; `SurveyScene.forCrossSection` is the whole difference |
| `control/graph/GraphView` — drawing and touch plumbing | `demo/.../SurveyCanvas.kt` | **Rewritten**, not ported |
| `control/threed/SurveyRenderer` — the camera | `shared/math/Camera3D.kt`, `Matrix4.kt` | Including `android.opengl.Matrix`, which exists nowhere else |
| `control/threed/*`, `ThreeDViewActivity` | `demo/.../ThreeDView.kt` | The GL half **rewritten** as a 2D canvas: no shaders, no vertex buffers, and it runs on all four targets |
| `GuideActivity`, `assets/guide/index.html` | `shared/manual/Manual.kt`, `demo/.../ManualView.kt`, `demo/src/commonMain/composeResources/files/manual.html` | The `WebView` **replaced** by a reader: the guide is bundled byte-for-byte and drawn as Compose, so there is no platform web view on any of the four targets. `parseManual` throws on a tag it does not draw, and the counts are checked against the file's own tags |
| `res/layout/activity_graph.xml` | `demo/.../App.kt`, `SketchToolbar.kt` | The 9x2 toolbar, copied |
| `res/values/colors.xml` (+ `values-night`) | `demo/.../SexyTopoTheme.kt` | The app's own palette |
| `res/drawable-hdpi/*.png` | `demo/src/commonMain/composeResources/drawable/` | The app's own icons |
| `res/menu/action_bar.xml`'s submenus | `demo/.../App.kt` (`MenuPage`) | File, View, Instrument, Settings and About, one page at a time — Material 3 has no nested `DropdownMenu`, so the one menu swaps its contents |
| `res/menu/drawing.xml` | `demo/.../SketchToolbar.kt`, `DemoState.BEHAVIOUR_TOGGLES`/`DISPLAY_TOGGLES` | Every checkable item on it except the neighbouring-survey one, in the menu's own three groups: the actions stay on the popup, the twelve toggles are a dialog. Hiding cross-sections stops them being tapped as well as drawn |
| `SketchPreferences.Toggle` | `demo/.../AppPreferences.kt` | All twelve persisted, which five of them were not until the menu was split |
| `action_fullscreen`, `GeneralPreferences.isImmersiveModeOn` | `demo/.../App.kt` (`FullScreenHandle`) | Hides the app's own bar rather than the system's, which is not this port's to hide; a drawn handle brings it back |
| `GraphView.drawCompass` | `demo/.../SurveyCanvas.kt` (`drawNorthArrow`) | The arrow, plan-only, at a heading of zero — which is *correct* on a plan; the magnetometer that would turn it is not ported |
| `model/sketch/Sketch`'s twin history stacks | `shared/sketch/SketchEditor.kt` | `DeletedDetail` becomes a sealed type |
| `control/io/thirdparty/{survex,therion,survextherion}` | `shared/io/export/` | Golden-tested, metadata block included |
| `ThconfigExporter`, `SurvexTherionUtil.getInputText` | `shared/io/export/SurvexTherion.kt` | The project file Therion actually compiles, and the `input` lines that pull the scraps into the `.th` |
| `DoubleSketchFileExporter`, `PLAN_SUFFIX`/`EE_SUFFIX` | `demo/.../ExportView.kt` (`fileNameFor`) | One file per drawing rather than per survey, for the three formats that have one of each |
| `PocketTopoFile`, `PocketTopoImporter` | `shared/io/imports/PocketTopoFile.kt`, `PocketTopoImport.kt` | A cursor over a byte array rather than an `InputStream`; the calendar arithmetic `new Date()` used to do, written out |
| `PocketTopoTxtImporter` | `shared/io/imports/PocketTopoTxtImport.kt` | The only import that brings a drawing in too; four crashes in the Java are fixed rather than reproduced |
| `SurvexImporter`, `TherionImporter`, `SurvexTherionImporter`, `SexyTopoVersion` | `shared/io/imports/` | Round-tripped against the exporters above; fixes a station-comment bug found doing so |
| `NewStationNotificationService` | `demo/.../Haptics.kt`, `AppPreferences.kt` | A haptic rather than a timed buzz on iOS, which has no public API for one |
| `control/Log`, `SystemLogActivity` | `shared/log/`, `shared/io/LogJson.kt`, `demo/.../LogDialog.kt` | Instances rather than statics, so it can be tested; the file format is the Android app's, `isError` written as a string and all |
| `model/table/LRUD` | `shared/survey/Lrud.kt` | |
| `model/survey/Trip` | `shared/model/survey/Trip.kt` | `java.util.Date` becomes a zoneless `SurveyDate` |
| `control/util/GraphToListTranslator` | `demo/.../SurveyTableView.kt` | Including as-taken normalisation |
| `SexyTopoActivity` and the Android UI shell | `androidApp/.../MainActivity.kt` | One `setContent { App() }` |

---

## Findings worth recording

These are the things that would actually shape a real port.

1. **The sketch model needed no translation at all.** Paths are already lists of the app's own
   `Coord2D` in survey metres and colours its own packed RGB ints — there is not a single
   `android.graphics` type in the sketch data. Only the *renderer* is platform-specific, which is
   why `GraphView` was the one thing that had to be rewritten rather than ported.

2. **Storage is where Android leaks into the domain.** `Survey` holds a `DocumentFile` and derives
   `equals`/`hashCode` from a `content://` URI, and the *file format itself* stores cross-survey
   links as those URIs. A port needs a platform-neutral link scheme plus a migration — which the
   Android app would benefit from anyway, since those links already break when a survey folder
   moves. This port carries an opaque `location` string instead.

3. **`kotlin.math.round` is not Java's rounding.** It is ties-to-even (it maps to `Math.rint`),
   while Java's `Formatter` — and therefore every number the Android app has ever written to a
   Therion or Survex file — is HALF_UP. `round(2.5)` gives 2, not 3. Any port must use
   `floor(x + 0.5)`; there is a test pinning it.

4. **The web target has no system fonts.** Skia on Wasm ships none, so *every* text draw throws and
   the whole page renders blank. Bisected by rendering a pure-drawing canvas (worked) against a
   single `Text` (threw). The app now bundles Liberation Sans, which also makes text render
   identically on every platform.

5. **An iOS port can drop a bug rather than carry it.** `Bric4Manager` cannot tell which of BRIC's
   three indication characteristics delivered a packet, so it cycles blindly through the roles and
   its own comment admits the desync risk. CoreBluetooth reports the characteristic on every
   callback, so routing by UUID makes that failure mode impossible.

6. **The instrument layer really does split cleanly.** ~2,450 lines of protocol logic are shared and
   byte-tested; the platform transport is ~230 lines of pure callback plumbing, with its decisions
   pulled out into a tested `GattLink`. That was the feasibility study's central claim about this
   layer and it holds up.

7. **Extracting the untestable code found bugs in it immediately, twice.** The first pass moved
   the profile matching out and exposed the UUID bug below. A later review of what was left found
   six more — a second `connect()` leaking a scanning central manager, a callback reporting a
   connection after the surveyor disconnected, Bluetooth being toggled silently reconnecting an
   app that had been disconnected, a connection reported before the subscriptions were confirmed
   (so a failed subscribe gave a "connected" instrument that recorded nothing), a missing
   characteristic producing silence rather than an error, and no timeout at all. Every one was a
   *lifecycle* question rather than a Bluetooth question, which is why they could all move to
   `GattSession` in `commonMain` and get a test each. The lesson generalises: when a file cannot
   be compiled, the useful move is not to review it harder but to make it smaller.

   Five of the six are fixed outright. The sixth, the timeout, is fixed only as policy: the rule is
   in `GattSession.tick` and tested, but nothing calls `CoreBluetoothTransport.checkTimeout`, so on
   iOS an instrument that is off or out of range still hangs the connection attempt. Driving it
   needs a run loop, which needs a host, which needs a Mac.

   And making the file smaller did not make it correct. A later review of the ~200 lines that remain
   found two errors that would stop it compiling at all — anonymous delegate types, and two pairs of
   Objective-C selectors that collapse onto one Kotlin signature — plus a missing `BetaInteropApi`
   opt-in, a failed scan that never stopped the radio, and a `disconnect()` that never reported
   itself.

   Then a macOS runner was added and the compiler had its say, which is the part worth keeping. It
   confirmed every one of those diagnoses, and it also showed that reading had got the *fixes* wrong
   twice: `@ObjCSignatureOverride` had been reasoned onto one half of each colliding pair, from the
   protocol's declaration order, and both times it was the wrong half. Three cycles of ninety
   seconds each settled what several hours of careful reading could not.

   So the lesson has two halves, and the second only arrived once there was a compiler to supply it:
   **make the untestable file smaller, and then go and test it anyway.** Small enough to review is
   not the same as verified. What made that possible here was noticing that GitHub gives public
   repositories free macOS runners — the Mac this project had been treating as a blocker was a
   nine-line CI job all along.

8. **The specific bug worth quoting.** The first
   `CoreBluetoothTransport` compared `CBUUID.UUIDString` against the profile's 128-bit UUIDs as
   plain strings. Assigned-number UUIDs — which is what BRIC4 and BRIC5 use for *all four* of their
   characteristics — have a 16-bit short form, and CoreBluetooth reports whichever width the UUID
   actually has, so none of BRIC's characteristics would have matched: the instrument would pair
   and then do nothing. Android's `UUID.toString()` is always 128-bit, which is why the original
   never had to think about it. Nobody would have found this without a Mac, a BRIC and a cave.

9. **The Java loader silently turns corruption into data loss.** A leg naming a station missing
   from the file becomes a *splay* if you resolve the name leniently, which detaches every station
   beyond it with no error — the port did exactly this until a review caught it. Saving a sketch
   also dropped every cross-section. Both are the worst failure mode a survey app has: the file
   still opens, and what is missing is a branch of the cave. Anything reimplementing this format
   should start from the tests in `SurveyLoaderFidelityTest`.

10. **The app's own reference calibrations would fail its own test.** Both 56-shot datasets in the
   Android app's test suite fit at about 0.60, and `DistoXCalibrationActivity.MAX_ERROR` — the
   threshold for a good calibration — is 0.50. So the data the algorithm is verified against is
   data the app would tell a surveyor to take again. The port reproduces the deltas to six figures,
   so this is not a translation error: either the threshold is optimistic or those two calibrations
   are mediocre, and nothing in the app says which. Worth settling before anybody reads much into
   the number.

11. **A throw inside a Compose composition is invisible on the web.** The cross-section editor
   appeared to do nothing at all: the tool ran, the hit test found the section, the state was set,
   the composition took the right branch — and the screen went on showing the plan. It was throwing
   during composition (`Survey.getSketch` rejects `CROSS_SECTION`, which the editor passes on
   purpose), and on Kotlin/Wasm that neither raises a page error nor blanks the screen; the last
   rendered frame simply stays up and stops updating. On Android the same mistake is a stack trace
   in logcat. Anything debugging a Compose Multiplatform web app should assume "the UI did nothing"
   means "something threw", and bisect by logging rather than by watching.

12. **Station comments grow four columns every time a survey round-trips through Survex.**
   `SurvexTherionImporter.parsePassageData` splits a passage row on the *first* run of whitespace
   and keeps everything after it as the comment. A row is `station left right up down comment`, so
   a station commented "junction" comes back in as `-	-	-	-	junction` — this app writes the
   four LRUD columns as hyphens, having no LRUD to put there — and exporting again writes *that*
   as the comment, so the next round adds four more. Two exports and two imports and the comment is
   unreadable. The port takes the sixth column onward instead, which is the only reading that
   round-trips, and is therefore a deliberate divergence rather than a translation of what the Java
   does. It is round-trip corruption of a surveyor's own notes, so it is worth fixing upstream too.

13. **The 3D view's gestures and its fit are both worth changing.** Two divergences, both in
   `ThreeDView.kt` and both deliberate. `SurveyView3D` pans with one finger and rotates with two —
   and reaching the rotate needs both fingers moving together *without* changing their spacing,
   which is also how a pinch starts, so the gesture the whole view exists for is the hardest one to
   perform. The first thing anybody does with a 3D view is drag it to spin it, so here one finger
   turns and two pan and pinch. Separately, `SurveyRenderer.buildGeometry` sets the camera distance
   to the longest side of the bounding box times 1.5, which ignores the shape of the screen: the
   field of view is *vertical*, so a portrait phone's horizontal one is much narrower and a cave
   fitted to the height hangs off both sides. Measuring what the cave actually projects to — three
   half-extents in the camera's own frame, then the distance at which two of them fit the frustum —
   is both correct and, on a phone, about twice as close.

14. **`PocketTopoTxtImporter` has four crash paths on files it should read or refuse.** All four
   are exceptions rather than wrong answers, and an import that crashes takes the app down with a
   file the surveyor then cannot get in at all. `parseDataAndUpdateSurvey` guards on
   `fields.length < 3` and then reads `fields[3]` and `fields[4]`. `getSection` calls
   `matcher.find()` without checking the result and then `matcher.group(1)`, so a file exported
   before anything was drawn — no PLAN block — raises `IllegalStateException`.
   `getOffsetForNamedStation` reads `tokens[2]` unguarded. And the offset fallback, reached when the
   origin station is not named in the drawing, calls `.minus(...)` on the offset it has just failed
   to find and looks up a projected position that can be absent too. The port refuses those files
   instead, and has a test for each.

15. **The `.top` reader trusts four bytes of a corrupt file with an allocation.** Every count in a
   PocketTopo file — trips, shots, references, points in a polygon — is read as a 32-bit integer
   and handed straight to `new ArrayList<>(count)`, then looped that many times. A truncated or
   mangled file is therefore an `OutOfMemoryError` or a very long wait rather than an error message.
   The port checks each count against the bytes that are actually left, which is a bound that costs
   nothing and cannot be wrong. Its string reader has the same shape of problem: the 7-bit length
   prefix is decoded in a loop with no width limit, so a run of high-bit bytes shifts past the width
   of an `int` and yields a small plausible length rather than an error.

16. **The two PocketTopo importers disagree about splays, and the text one is wrong.** The binary
   reader attaches legs directly and its comment says why — "to avoid triple-shot detection creating
   unwanted auto-named stations during import". The text reader hands every splay to
   `SurveyUpdater.update`, which applies exactly that rule. So a surveyor who shot three careful
   splays off one station — a passage wall measured properly — gains a station that is not in the
   file, auto-named, with the rest of the import hanging off it. Two importers in the same package,
   one of which documents the trap the other falls into.

17. **Every walk of the survey tree overflows the stack on a real cave.** The Java recurses once
   per station — `Space3DTransformer`, `SurveyTools.traverseStations`, `getAllStations`,
   `collectStationsWithComments`, the `extend` commands, `setExtendedElevationDirectionOfSubtree`.
   A cave is not a bushy tree: a passage is a *chain*, so the recursion is as deep as the survey is
   long. Somewhere between one and three thousand stations, on a desktop JVM with a generous stack,
   it falls over — and the first thing that touches it is the plan view, so opening a club's survey
   crashes before anything is drawn. A phone's stack is smaller, and Kotlin/Wasm's smaller still.
   Every one of them is a loop here, and there is a four-thousand-station test on all three targets.
   Worth fixing upstream: it is the difference between an app for a weekend's surveying and one for
   a cave.

18. **Exporting a large survey was quadratic.** `chronologicalEntries` ordered the legs with
   `indexOfFirst` inside the sort key — a scan of the whole record per leg. At thirty thousand
   stations a Survex export took **eighteen seconds** on a desktop; indexing the record once first
   brings it to **114 milliseconds**, and the output is byte-identical. Ported from
   `GraphToListTranslator.toChronoListOfSurveyListEntries`, which has the same `indexOf`.

19. **A survey read from a file can contain a cycle, and walking one has to stop.** The formats
   name a leg's far end, and nothing in them forbids two stations of the same name — which collapse
   on load into a leg pointing at its own source. The recursion met that as a stack overflow; a
   plain loop meets it by never finishing, which is worse, because `checkSurveyIntegrity` is the
   thing that would report the file as broken and it begins by walking the tree. `getAllStations`
   now remembers where it has been.

20. **A Compose chip eats the drag that would scroll the row it is in.** Not a finding about the
   Android app — about this port, and about Compose. The export screen offers eight formats, which
   do not fit across a phone, so they sat in a horizontally scrolling row. A drag that *begins on a
   chip* moves that row about thirty pixels and then stops, however far the finger goes; a drag
   that begins in the gap between two chips scrolls it normally. A finger lands on a chip far more
   often than in a gap, so in practice half the export formats could not be reached at all. Wrapping
   the chips fixes it and needs no gesture. Worth knowing before putting a scrolling row of
   `FilterChip`s anywhere a finger has to drag it.

21. **The vibrate-on-new-station setting says on and behaves as off.** `preferences_general.xml`
   declares `android:defaultValue="true"` for `pref_vibrate_on_new_station`, so the checkbox on the
   settings screen appears ticked on a fresh install. But nothing in the app calls
   `PreferenceManager.setDefaultValues`, and a `defaultValue` is not written to `SharedPreferences`
   merely by being displayed — so the key is absent, and `NewStationNotificationService` reads it
   with `getBoolean(key, false)`, which returns false. The app therefore tells a new user that it
   will buzz when a station is made, and does not, until they toggle the box off and on again. This
   port takes the settings screen at its word and defaults it on. (While reading that class: its
   `onStartCommand` assigns the receiver to a *local* variable that shadows the field, so `onDestroy`
   unregisters a null and the receiver is never removed. Harmless — `LocalBroadcastManager` ignores
   a null — but it is a leak.)

22. **The Android sketch has a live control it never draws.** `GraphView.didEventHitHotCorner`
   tests all four corners of the view; `drawHotCorners` tints three of them — top-left, top-right,
   bottom-right. The bottom-left corner therefore pans the drawing while nothing on screen says it
   is anything at all, which from the surveyor's side is the app losing a stroke. This port draws
   all four, which is a smaller change than making the fourth inert and is what the touch handler
   already does.

23. **Pinch-to-zoom is not a tool, and treating it as one costs two taps per pan.** In the Android
   app the `ScaleGestureDetector` is consulted *before* the tool switch, so two fingers zoom
   whatever is selected; the hot corners and the two-finger drag do the same for panning. This port
   had put pan and zoom inside the pan tool, so moving the drawing while drawing it meant MOVE,
   drag, DRAW again — two toolbar taps for the single most frequent thing anybody does to a sketch,
   on a phone, in a wet oversuit. It is not a bug anything would report as one: every individual
   feature works. Compose has no ready-made detector for "only once there are two fingers"
   (`detectTransformGestures` fires for one), so the canvas now watches the pointers itself and
   consumes only once it has taken the gesture over, which is what lets the tool's own detector go
   on working untouched the rest of the time.

24. **A dialog opened while the finger is still down is dismissed by that finger coming off.** The
   station menu is reached by a long press, so on Android the menu appears under a finger that has
   not yet lifted — which is fine there, because the view that detected the press has captured the
   touch. In Compose the dialog goes up as a new layer immediately, the release lands on its scrim,
   and the menu closes again. It looks like the app *nearly* having the feature: hold, and the menu
   is there; let go, and it is gone. And it only happens when the press is somewhere the dialog
   does not cover — near the bottom of the screen, which is exactly where the stations at the
   working end of a survey are, so the first place anybody tries is the place it fails. The menu
   here therefore opens on release rather than on the hold. Worth knowing before anybody wires a
   long press to a dialog on this stack.

25. **A settings screen that builds its result from the switches it shows resets the ones it does
   not.** This port's own bug, caught by ordering a browser check so that a preference set from the
   drawing menu had to survive a visit to the settings screen. `AppPreferences` is a data class, so
   `AppPreferences(buzzOnNewStation = …, hotCorners = …, twoFingerMove = …)` compiles perfectly and
   silently returns every other preference to its default: turning *Follow the survey* on halfway
   down a passage and then loosening a tolerance turned it off again, with nothing on screen to say
   so. A `copy` of what came in is the fix, and the general lesson is that a constructor call with
   named arguments looks exactly like a partial update and is not one.

26. **A ported engine can be complete and still unreachable, and the tests will not notice.**
   `reverseLeg`, `downgradeLeg` and `promoteToAboveLeg` were ported early, along with the Java's own
   tests for them, and passed on every target for months. Nothing in the app could ask for any of
   them: the leg dialog offered edit, upgrade and delete, and the README had already claimed
   "reverse" in a sentence about the station menu, because the dialog it opens *is* the leg dialog
   and nobody had counted its buttons. A unit-tested function with no caller is indistinguishable
   from a working feature in every report the build produces. What found it was reading the Android
   app's `context_leg.xml` against the port's own dialog, item by item — which is the only method
   that works, and is worth doing for every menu rather than trusting the test count.

27. **A dialog laid out from the data cannot be clicked by coordinate.** Adding two buttons to the
   leg menu moved *Edit reading* onto *Splay comment*, so the browser check went on typing "2.75"
   into a comment field and reporting that the correction had not been saved — a true failure with
   a misleading cause, which is the expensive kind. Finding 25 was the same shape. The fix is the
   same one both times: find the row rather than counting pixels from the top of the screen. A menu
   whose length depends on what the survey holds — a leg is offered a reverse and a splay is not,
   and a leg with a cave hanging off its far end loses its downgrade — has no fixed coordinates to
   hard-code, and the check that locates its rows can then *assert on how many there are*, which is
   the thing actually worth checking.

28. **A number in a `DrawScope` is a physical pixel, and the checks run where that does not
   show.** Found while measuring the dashes: `DASHED_LINE_INTERVAL_DP` was being handed to the
   drawing code as a plain `4f`, where the Android app puts every one of these through
   `dpToPixels`. Looking for company found the whole canvas doing it — leg and splay widths,
   station dots, cross-section marks, the grid, the scale bar, the eraser's reach ring, and the
   same again in the 3D view. On a phone at three device pixels to the dp that is a cave drawn at a
   third of its size: a hairline centreline and pinhead stations, with the station *names* beside
   them the size they should be, because those are measured in `sp` and Compose does scale that.
   The touch tolerances were all converted properly, which is what kept it hidden — the app would
   have felt right and looked wrong. And nothing here could have caught it: the browser the checks
   run in is at one device pixel to the dp, where every one of these numbers is its own conversion,
   so the fix changes not a single pixel of any evidence in this repository. It was the one change
   on this branch verified by reading rather than by running — until finding 37, where it turned
   out `ImageComposeScene` takes a `Density` and a phone is only a number. It has a test now.

29. **The thing that looked like the bottleneck was not, and the measurement said so.** With the
   whole of a four-thousand-station survey on screen — the view the app opens on — a frame took
   about 170 ms in the headless renderer. Twelve thousand separate `drawLine` calls looked like the
   obvious culprit, and the Android app already batches its dashes through `Canvas.drawLines`, so
   gathering the segments into one `drawPoints(PointMode.Lines)` per colour looked like the obvious
   fix. Measured, it was **no faster at all** — the cost is rasterising twelve thousand antialiased
   round-capped segments, not the calls that ask for them — so it was taken out again rather than
   kept as complexity that pays nothing. What *did* pay was the cull the Java has and this port did
   not: zoomed into one passage, 16.6 ms a frame becomes 14. Two cautions about those numbers, both
   in the test: `ImageComposeScene` rasterises on the **CPU** where a phone uses its GPU, so none of
   them are a phone's; and the ratio that matters is the one that says the draw path is *linear* in
   the size of the survey — 500 stations to 4,000 costs 22.6 ms to 183.8, which is 8.1 times for
   eight times the cave. That is the property worth a test, and it is the one that would have
   caught finding 18 in the drawing rather than in the export.

   The other half of that measurement is a saving the Java has not got. `drawStations` walks every
   station in the survey, and a station is not only a dot — if the names are showing, each one
   *measures a piece of text*, which is far more work than the circle. Culling those the same way
   took the zoomed-in frame from 14 ms to **9.3**, a bigger saving than the legs. The margin has to
   allow for the name, which is drawn up and to the right of the dot: cull on the dot alone and
   labels flicker at the edge as the drawing is dragged.

   And a third measurement, which said *don't*. The drawing itself is not culled, and it looks
   exactly like it should be: thousands of strokes, each mapped into screen coordinates and built
   into a path every frame whether or not any of it is showing. Eight thousand of them cost **67 ms
   a frame** with all of them on screen and **0.4 ms** with almost none — inside the noise.
   Preparing a stroke is cheap; what cost the time was rasterising it, and rasterising is what Skia
   already skips. Two of the three guesses about where the time went were wrong, which is the whole
   argument for the test existing. The 67 ms is left as it is on purpose: it is rasterising strokes
   that are genuinely on screen, so no cull touches it, and drawing less of the drawing is a
   decision about what a surveyor sees rather than a performance fix.

30. **The same lesson, twice more, and then a guard for it.** Finding 27 was a dialog laid out
   from the data that could not be clicked by coordinate. The drawing menu then did it again in a
   worse way: at eighteen rows it grew taller than the room above the toolbar, so Compose
   repositioned the whole popup to fit the screen, *every* row moved, and ten checks failed with
   messages about cross-sections and hot corners and none about menus. The fix is the same one —
   find the menu's own surface and divide it by the rows it is known to have.

   Then a third time, and this one is the clearest of the three. The export screen's format chips
   are a `FlowRow`, and adding the `.thconfig` reflowed them from three rows to four. The check
   that had been clicking "second row, middle chip" now clicked *Tracing .xvi* — and went on
   passing, against the wrong format, until the filename it asserted disagreed; Save file moved
   fifty pixels down at the same time and landed on a chip. So the chips are found now as well: a
   chip is the only thing drawn on that screen's background, so its edge is a long horizontal run
   of not-the-background, and the runs on a row's top edge are the chips.

   Which promptly found *twice* as many chips as there are, and is worth recording because it is
   the same mistake in the finding: the first version grouped rows by looking for a gap between
   them, and a row shows its chips at its top edge and again at its bottom edge, with nothing in
   between unless one of them happens to be the selected one. Assuming the shape of what you are
   looking for is what all four of these have in common.

   The other repeat was cheaper to fix and had cost more: four separate times, a pixel helper has
   passed `page.evaluate` a name that is not in scope out here. The callback destructures the
   arguments under its own names, so nothing checks the two against each other, and the error
   arrives when the helper is first *called* — which on the slow path is minutes in and reports
   itself as whatever check happened to be running. `field.mjs` now reads its own source before it
   launches a browser and refuses to start if any of those names is undeclared. Written after the
   fourth time, which is three times later than it should have been.

31. **A pixel threshold tuned on one glyph reports a working feature as broken.** The check that
   the table gains a dagger against a commented leg counted pixels darker than 120 and found the
   cell unchanged — 66 before, 65 after — so the check failed while a screenshot of the same moment
   showed "† 5.420" perfectly legibly. A dagger at 12sp is one hairline stem and a crossbar, and at
   that size almost every pixel of it is antialiased grey somewhere above the threshold that "5.420"
   had comfortably cleared. Summing *how much darker than the paper* each pixel is, rather than
   counting the ones past a cliff, measures thin ink and thick ink alike. Worth remembering because
   the failure looked exactly like the feature being missing, and the cheapest way to tell the two
   apart was to take the screenshot and look at it.

32. **A preference that is not stored anywhere is not a preference.** Five of the drawing menu's
   toggles — the splays, the sketch, the labels, the grid and the snapping — were `mutableStateOf`
   on the app's state object rather than fields of `AppPreferences`, so a surveyor who turned the
   splays off got them back on the next run. Every one of them is a persisted
   `SketchPreferences.Toggle` in the Android app.

   What hid it is worth more than the bug. They *worked*: the menu ticked, the drawing changed, and
   every check that exercised one passed, because each turned it on and off again within the run.
   Nothing about reading `state.showSplays = !state.showSplays` suggests a missing file write; the
   line looks exactly like the six toggles beside it that do persist, which reach the same value
   through `state.preferences`. It was only found by going through `drawing.xml` group by group to
   split the menu — which is to say, by comparing against the original rather than by testing what
   was here. The check that would have caught it is now in `field.mjs`: turn the grid off and look
   in storage for it.

33. **An export nobody could use, and nothing said so.** The port wrote a `.th`, both `.th2`
   scraps and both `.xvi` tracing images, every one of them golden-tested against the Android app
   byte for byte. It was still not a Therion export. Therion does not compile a `.th`; it compiles
   a *project*, and the project file — the `.thconfig` — was not written at all. Nor did the `.th`
   carry the `input` lines naming its scraps, because it had been written when this port had no
   `.th2` exporter and the comment saying so outlived the thing it described. So a surveyor got
   five correct files, no way to build them, and a centreline with no cave on it if they wrote the
   config themselves.

   Underneath it, a worse one: the `.th2` and the `.xvi` are one file *per drawing*, and this port
   named them after the survey. Exporting the plan and then the elevation wrote `Swildons.th2`
   twice. The Android app has a whole class for this — `DoubleSketchFileExporter`, which puts
   `PLAN_SUFFIX` or `EE_SUFFIX` in the name — and the port had reimplemented the exporters without
   it. Two files that are the same format of the same cave are indistinguishable once written, so
   the surveyor's evidence that something went wrong is a drawing that is not the one they exported.

   The lesson is about what a golden test is worth. Each file was right; the *set* was wrong, and a
   test that asserts one file at a time cannot see that. What found it was reading the Java's
   `TherionExporter.run` — the method that decides which files there are — rather than the classes
   that write them.

34. **The same mistake, in the other menu, half an hour later.** Adding *About* took the overflow
   menu to fourteen rows plus one per saved survey. Fourteen rows is 672 pixels; an iPhone SE is
   667. So the last row — the licence and the authors, added precisely so a user could see them —
   was drawn half off the bottom edge, on the device this whole port exists for.

   It was not *unreachable*: Compose scrolls a popup that does not fit, so it could be scrolled to.
   That is what made it a bug rather than a crash, and it is why the browser check did not catch it
   the way it caught the drawing menu — the row arithmetic simply pointed at the wrong item and
   opened *Import a survey* instead, and the screenshot beside the failure is what said so.

   The fix is the one the drawing menu got, from the same source: `action_bar.xml` is seven
   top-level items with submenus, and this port had flattened them. It is five rows now — File,
   View, Instrument, Settings, About — with the saved surveys inside File where the app's own Open
   is. Twice in one sitting the answer to "this menu is too tall" was in the file being ported
   from, and twice I had flattened it away first.

35. **The Xcode project was the half nothing had ever compiled.** CI built Kotlin five ways —
   simulator, Kotlin/Native tests, the Compose framework, the device target, the platform code in a
   simulator — and never once opened the Xcode project. But `project.yml`, `Info.plist`,
   `Assets.xcassets` and the two Swift files were all written on Linux by somebody who could not
   run Xcode, and every one of them is a way to produce an app that builds and is wrong: a
   catalogue that ships no icon, a `UIColorName` matching no colour set, a plist key misspelt.
   `xcodegen` plus `xcodebuild` is one CI step and it compiles all of it, `actool` included.

   It passed first time, which is the boring outcome and the right one. It also surfaced the only
   thing in that build worth repeating: `libicu.icudtl_dat.o` inside Compose's own framework is
   built for iOS-simulator 17.2 while this project declares a minimum of 15.0, so the linker warns.
   The name is the reassuring part — `icudtl_dat` is ICU's *data* table compiled to an object file,
   so it references no API and the warning is about provenance rather than behaviour. Worth saying
   out loud anyway: **nobody has run this on anything older than the runner's simulator**, so 15.0
   is a declared minimum rather than a tested one.

36. **The picture was the check that was missing.** Everything about iOS here was inference. The
   framework links, so Compose is probably fine. The platform code passes, so the file store is
   fine. The Xcode project builds, so the app is probably fine. None of it drew a cave, and drawing
   a cave on iOS is the entire question this branch exists to answer.

   `xcrun simctl` does it in five lines: boot, install, launch, wait, screenshot. What took the
   thought was making it a *check* rather than a photograph, because a photograph nobody looks at
   proves nothing on the run where it matters. The app's panel green is the obvious test — a crash
   shows Springboard and an empty Compose canvas shows white, and neither has any of it.

   Except the launch screen is that same green, chosen on purpose to make the first moment look
   like the app starting. So an app that hung before its first frame would pass a green-only check
   looking *exactly* like a working one — the one failure mode most worth catching, invisible to
   the obvious test. Hence the second condition: a drawn survey is hundreds of distinct colours
   and a launch screen is two. Measured, both ways, against a real frame and a synthetic launch
   screen, before it was ever pushed.

37. **"Only a phone can check this" was wrong, and the first test I wrote for it was vacuous.**
   Finding 28 — every drawn size on the canvas a raw pixel rather than a dp, so at three device
   pixels to the dp the cave came out a third of its size — was found by reading and fixed by
   reading, and I recorded that only a phone could confirm it, because the browser CI runs at one
   device pixel to the dp and cannot see the difference. That was a failure of imagination.
   `ImageComposeScene` takes a `Density`, and a phone is only a number: render the same survey at
   1x and at three times the size *and* three times the density, and the second is what a 3x
   screen shows. If the sizes are dp the two are one picture at two resolutions and the same
   *proportion* of each is ink; if they are raw pixels the second is drawn a third as thick.

   Then the interesting part. The test passed — and passed just as happily with finding 28 put
   back, which is the only reason I found out it was worthless. It counted every dark pixel, and
   most of what is on that canvas is *text*: station names, the scale bar, the compass. Text is in
   `sp`, which scaled correctly the whole time finding 28 was live. So the measurement was
   dominated by the half that was never broken, and the ratio fell only from 0.98 to 0.77 —
   inside any threshold loose enough to tolerate antialiasing.

   Counting only the red centreline, the thing actually drawn from a dp, separates them properly:
   1.11 as the code stands, 0.44 with the bug reintroduced. The 1.11 is antialiasing and it errs
   honestly — a 2.5px line spends much of its width in half-covered edge pixels too pale to count,
   while at 7.5px the line is mostly solid core, so the fraction rises slightly with density.

   Two lessons, and the second is the one I keep relearning: a test that has never been seen to
   fail is not evidence, it is decoration. Every check on this branch that claims to catch
   something was run against the bug it names before it was pushed — this one twice, because the
   first version of it was a lie I told myself in good faith.

38. **The lesson I drew from a real bug was wrong, and it cost the app its typography.** Early on
   this port shipped "✓" beside every checked menu item and every one came out an empty box: the
   app bundles Liberation Sans, because Skia ships no system fonts on the web and text otherwise
   does not draw at all, and Liberation Sans has no Dingbats. That much was true, and the tick is
   drawn by hand to this day for a good reason.

   The rule I wrote down from it was *distrust any glyph outside Latin-1*, and I put it in three
   code comments, in a test, and in every decision after it. So the About box got "-" for its
   bullets, the submenu rows got ">" for their chevrons, and each carried a comment stating as
   fact that the bundled font had no bullet and no chevron.

   It has both. Asked directly — `FontMgr.default.makeFromFile(...).getUTF32Glyphs(...)`, which is
   the same Skia that does the drawing — Liberation Sans resolves "•" to glyph 2030, "›" to 2043
   and "→" to 2118 in *both* weights, and 57 of the 112 characters of General Punctuation
   besides. Only "✓" and "⋮" come back as glyph 0, which is exactly the two the app draws.

   What made this stick was that the test I wrote to enforce it asserted `code > 0xFF`, which is
   wrong in both directions at once: it passes the control characters, which no font has a glyph
   for, and fails "•", which this one does. It never disagreed with the code because it was the
   same guess written twice. It now asks the font, and so does `FontCoverageTest`, which asserts
   both halves — every character the app types resolves, and every mark the app draws does not —
   so the next person to wonder gets an answer instead of an anecdote.

   The bullets and chevrons are back. Every browser check still passes, and the menu and the
   About box were photographed to be sure the glyphs *render* and not merely resolve.

39. **The manual, and why it is not a web view.** `GuideActivity` puts a 23 KB HTML guide in a
   `WebView`. This README listed that as blocked on a decision — a web view is a *platform* view,
   so it is a `WKWebView` behind a UIKit interop on iOS, an iframe positioned over the Compose
   canvas on the web, and nothing at all on the desktop — against reading the HTML and drawing it
   as Compose, which is one implementation but drifts the first time upstream edits the guide.

   The guide is eight tags wide: `h1`–`h3`, `p`, `ul`, `ol`, `li`, `strong`, `em`, `code`, `a`.
   Three platform views for that is the wrong trade, so it is parsed — and the drift is paid for
   rather than hoped away. `parseManual` **throws** on any tag it was not written for, naming it,
   and `ManualContentTest` parses the shipped file on every build. Upstream adding a table breaks
   this build; it does not quietly lose a section.

   Which is exactly the failure I then shipped anyway, in a form the throw could not catch. The
   reader parsed the whole guide without complaint and produced **69 list items where the file has
   79**: the guide nests one list inside another, under *Import*, and the inner `</ul>` was taken
   for the end of the outer one, so the eleven items after it vanished. Every tag was a known tag.
   The guard was for tags, and the loss was in an *arrangement* of tags.

   What caught it was counting — and what makes the count worth anything is that it counts against
   the file's own tags rather than against a number I wrote down: `<p` opens against paragraphs,
   `<li` against items, the three heading tags against headings. All three are now exact. The same
   pass had produced 109 blank paragraphs from the guide's own indentation, which the count also
   named.

   Two other things fell out of doing it. The manual is the app's first screen made of nothing but
   text, and every character in it — the arrow among them — is checked against the bundled font by
   the machinery from finding 38. And restoring `help_menu` put *Manual* and *About* where
   `action_bar.xml` has always had them, which exposed that the overflow menu resized between
   pages: 164 pixels on the top page, 112 on Help, so a submenu shrank under the finger that had
   just opened it. One width, and it stopped.

40. **A feature withheld on a misreading of its own name.** `action_bar.xml` offers four input
   modes and this port offered three. The fourth, `CALIBRATION_CHECK`, was ported into the engine
   and tested there, then deliberately kept out of the UI with a comment explaining that it holds
   readings taken against a known baseline and so is useless on a build that cannot talk to a
   DistoX.

   `strings.xml` calls it **Splays Only**. What it does is stop readings promoting to stations at
   all — and wanting that has nothing to do with instruments. A surveyor taking a run of splays
   round a chamber does not want three that happen to agree planting a station in the middle of the
   floor. I had read the enum's name, not the string the user sees, and written a paragraph of
   confident reasoning on top of it.

   Adding it back turned up the same structural fault a third time. Three chips filled the reading
   dialog's width exactly and they sat in a `Row`, which clips rather than wraps — so the fourth
   would have been off the edge of the card with nothing to say it was there. Findings 30 and 34
   again, and the fix is the same shape: `FlowRow`.

   It also broke five coordinates in `field.mjs` at once, which is the more useful half. Four chips
   wrap onto two rows, so the card grew fifty pixels — and a *centred* card that grows moves both
   its edges, so every field shifted up while every button shifted down. `FIELD_DISTANCE` landed on
   the bottom edge of its own box. They are offsets now: from the card's top edge for everything
   above the chips, from its bottom edge for the buttons. Both survive the card changing height,
   which is what a dialog does whenever anything is added to it — and something is added to a
   dialog rather often.

41. **Every check in this file was written for one screen size, and it showed the moment there was
   a second.** Adding a landscape pass — an iPhone SE turned over is 667x375, which is roughly the
   height a portrait phone has left when a keyboard takes a third, so it covers the layout half of
   the case this README had been calling untestable — broke four things at once, and none of them
   was the app.

   The overflow button was `[box.width - 16, 26]` evaluated *once*, at load, on the 420-wide
   layout. The submenu tap was a bare `312`, and the delete cross on a saved survey a bare `392`;
   both are the 420-wide layout's numbers. And all three dialog helpers decided "this row is the
   card" by counting more than **half the screen width** of card colour — which works until the
   screen is 667 wide and the card, which Material does not stretch, is 330. Half of 667 is 334.
   The dialog was open, on screen, entirely usable, and its own detector reported it missing by
   four pixels.

   The repair in each case was to measure from something that moves with the layout rather than
   from the origin: the current width for the button and the menu, and an absolute run of 200
   pixels of card colour for the card. That last one is the general lesson — *half the screen* was
   never the property being tested. The property is "there is a card here", and a card is a size,
   not a fraction.

42. **A survey imported without its drawing, and every check said it worked.** A SexyTopo survey is
   four files — `Name.data.json`, two sketches and a version stamp — and this port's importer takes
   one file at a time. So a survey handed over by somebody else parsed the centreline through
   `SurveyJson` and never looked for the sketches beside it. The drawings were dropped in silence.

   That is the worst shape a bug can have. It succeeds, it reports success, and what goes missing is
   the part nobody can reconstruct from what is left: the numbers are a minute a station, the
   drawing is the whole trip.

   `SurveyStorage` has read all four files for as long as it has existed; it was only the
   *loose-file* path that did not. And the reason nothing caught it is worth more than the fix. The
   browser check's import fixture was a survey of two stations, one leg and **no drawing**. It
   imported perfectly. The check was real and the assertion was real; the fixture simply could not
   express the failure. It has a drawing in it now.

   The same missing idea cost a second bug alongside it. Deciding "is this a survey?" by *any* file
   ending `.json` offered `Swildons.plan.json` in the import list beside `Swildons.data.json` — so
   the app invited you to import a drawing as a centreline. Both come from treating a survey as a
   file rather than as a *set* of files, which is finding 33 in another costume: a Therion export
   of five individually perfect files that together could not be built.

   Following that thought one step further found a third: a survey almost never arrives as a loose
   file. It arrives as a **zip**, and unzipping one in the Files app leaves a folder named after
   the cave with the four files inside — which the import list, looking only at files, could not
   see at all. The app would show an empty list beside a survey sitting right there. Root
   directories that pass `SurveyStorage.isSurveyDirectory` are offered now, and loaded through the
   library's own loader, which has read all four files since the day it was written.

   And then the mirror of the first one, which is the worst of the four: `NATIVE` **export** wrote
   `Name.data.json` and nothing else. Having taught the app to read a complete survey, it could
   still only write an incomplete one — and that half is worse, because a reader's failure loses
   somebody else's work while a writer's loses your own. One press of *Save files* now writes the
   data file and both sketches, which is precisely what the folder import reads back.

   Four bugs, one missing idea, found in an hour by asking the same question in four places. None
   of them was subtle once the question was asked; all four had passing tests over them.

   And then a fifth, committed by the fix for the first. Reading the sketches beside the data file,
   I wrapped the parse in a `runCatching` that threw the failure away — so a survey whose plan file
   was *present and damaged* imported with an empty plan and said nothing, and a caver would
   conclude the sender had never drawn anything. That is the same silent loss, one level down,
   introduced by the commit that fixed it. Absent and unreadable are different things and only the
   second is worth a word; the library has a warning channel for it now, separate from the one that
   means "could not save", because a save worth retrying and a damaged file are different advice.

   And a sixth, in the fix for the fifth, caught by asking one more question of it: *when does this
   stop being true?* A warning set once and never cleared would have sat in the app bar long after
   the surveyor had opened a different cave — a true sentence about the wrong survey, which is a
   worse kind of wrong than no sentence at all. It is cleared where a different survey becomes the
   one on screen. That single line is the only thing in this stretch without a test of its own:
   every path to it goes through the real filesystem store, which nothing else here writes to on
   purpose, and inventing a test that scribbles in a user's application-support directory would be
   a worse trade than saying this sentence.

   Sweeping the rest of the port for the same shape — a `runCatching` whose failure goes nowhere —
   turned up one more, and a difference from the Java worth passing on. `SketchJson` parses every
   stroke, symbol, label and cross-section inside its own guard, so one damaged mark costs one mark
   and the rest of the drawing survives. `SketchJsonTranslater.populateSketch` does not: its loop
   over paths sits *inside* a single `try`, so one bad stroke throws out of the loop,
   `setPathDetails` is never reached, and **the whole plan is lost**. (Its symbols loop has an inner
   try and behaves like this port's; its paths, labels and cross-sections do not.)

   Being more forgiving is only an improvement if it is not also quieter, and it was: the Java logs
   each of those failures and this said nothing, so a drawing that arrived three strokes short
   looked exactly like a drawing that was drawn three strokes short. The reader counts what it
   dropped now, and both the importer and the library say so.

   Saying it on **open** matters more than saying it on import, which is not the order I came to
   it in. This app saves on every change, so a survey opened three strokes short is written back
   without them the moment anything is edited: the damaged file was at least still damaged, and
   after that it is tidily, permanently short. The warning is what lets a surveyor copy the file
   somewhere before touching the survey.

   The fifth answer was not a bug but a false claim of mine. Asking the same question of the fourth
   file — `Name.metadata.json` — found that this port does not read or write it *at all*, while the
   pull request said the cross-survey links it carries "are read and written". They are not. The
   claim is corrected rather than the code, because the obvious fix is worse than the loss: that
   file also holds `active-station`, which the Android loader reads *after* the data file, so
   carrying it through untouched would silently override the station a surveyor had just been
   working at. What the round trip loses is now written down exactly.

43. **One damaged stroke loses the whole plan.** Upstream, and found by sweeping this port for
   discarded failures rather than by looking for it. `SketchJsonTranslater.populateSketch` reads
   the paths like this:

   ```java
   try {
       JSONArray pathsArray = json.getJSONArray(PATHS_TAG);
       List<PathDetail> pathDetails = new ArrayList<>();
       for (JSONObject object : IoUtils.toList(pathsArray)) {
           pathDetails.add(toPathDetail(object));   // throws
       }
       sketch.setPathDetails(pathDetails);          // never reached
   } catch (Exception e) {
       Log.e(R.string.file_load_sketch_paths_error, e);
   }
   ```

   The `try` is outside the loop, so one stroke that will not parse throws past
   `setPathDetails` and the sketch keeps the empty list it started with. Not that stroke — every
   stroke. The same shape reads the labels and the cross-sections. The *symbols* loop, twenty lines
   away, has an inner `try` per element and loses only the bad one, which is the giveaway that the
   other three are an oversight rather than a decision.

   It is logged, so the evidence exists on a phone nobody is looking at; on screen a surveyor gets
   a plan with the centreline and no drawing on it, and no reason given. The fix is to move the
   `try` inside the loop in the three places, as the symbols loop already does.

44. **The document's strongest sentence had nothing checking it.** This README's central claim is a
   table of rows marked **Verified**, and most of them back that word with the name of a test.
   Nothing read those names. A test renamed, deleted, or never written would leave the citation
   pointing at nothing, and it would be invisible in both directions: nothing here reads the README,
   and nothing in the README reads the tests.

   `ReadmeReferencesTest` checks them now — every backticked name ending in Test must be a class
   that exists, in this port or in the Android app, whose fixtures several of these claims are
   measured against. It cannot check that a test *asserts* what the row claims; no regex will. What
   it catches is the citation that has quietly stopped pointing at anything, which is the failure
   that actually happens.

   It is strict enough that this very paragraph tripped it: a backticked placeholder standing in
   for a test name read as a citation of a test called exactly that, and the check failed on prose
   about itself. Which is the correct behaviour and the reason the sentence above is worded the way
   it is — there is no way for the document to name a test it does not have.

   Its first run reported `PocketTopoImporterTest` as missing, which was the check being wrong
   rather than the README: that is a real Java test in `app/`, and being able to cite the original's
   own fixtures is the point of crediting it. Widening the search to both trees is the fix; the
   version that only looked in `kmp/` would have been a check that failed on correct documents,
   which is worse than no check at all.

45. **The port shipped the bug it reported.** `PocketTopoTxtExporter` and `SvgExporter` being
   unreproducible is one of this branch's findings about the Android app: `Space` keys its station
   and leg maps on `Station` and `Leg`, neither of which overrides `hashCode`, so iteration follows
   identity hash codes and the same survey exports differently the next time it is built. Both were
   repaired here, one exporter at a time, by choosing a defined order.

   The `.xvi` exporter was written later and walks `space.legMap.values` directly, so it inherited
   exactly the defect the README credits this port with fixing. Two exports of the same survey came
   out with the shot lines in different orders.

   What found it was writing the test badly first. Asserting that exporting *twice* gives the same
   file passes trivially: one set of objects has one identity-hash order, so a hash-ordered
   exporter agrees with itself all day. Rebuilding the survey between the two exports is the actual
   property — fresh objects, fresh hashes — and it is how the original's unreproducibility was
   found in the first place. The weak version passed on all twelve formats; the strong one failed
   on its first run.

   The fix is one word in the right place: `Space` holds `LinkedHashMap`s now, so insertion order —
   the order the survey was walked, which is the order it was surveyed — is the order everything
   reads. Repairing it at the source rather than in the exporter fixes it for every reader of a
   `Space`, including the ones nobody has written yet, which is exactly what the two one-at-a-time
   repairs did not do.

   The rest of that sweep came back clean: all four file stores sort their listings, the SVG
   legend's symbol set is a `LinkedHashSet`, and the remaining sets are membership checks that
   never reach a file. `Space` was the only one.

46. **A warning that cries wolf is worse than no warning, and I nearly shipped one.** Counting the
   marks a damaged drawing loses (finding 42's tail) meant deciding what counts as lost — and the
   cross-section is a trap. It names the station it was cut at, and deleting a station does *not*
   delete the drawing at it, neither here nor in the Android app, deliberately: the drawing is the
   surveyor's work and not a view of the graph.

   So a perfectly good sketch file can hold a cross-section whose station is gone. The reader skips
   it *by design* — `toCrossSectionDetail` returns null when the survey has no station by that name
   — and my counter could not tell that from a mark that would not parse. The result would have
   been "1 mark of the drawing could not be read" on **every open, forever**, of a survey whose
   only sin was that somebody deleted a station after drawing at it.

   An orphan is not damage, and the two are told apart now: a cross-section that names a station
   the survey does not have is skipped in silence, and one that names no station at all, or fails
   to parse, is counted. Found by asking what the new warning would say about ordinary use rather
   than about the case it was written for.

47. **A station called "sump 2" makes a Survex file that will not read, and nothing says so.**
   Upstream. `Station.FORBIDDEN_CHARS` is `\n` and `\r` and nothing else — this port matches it
   exactly — and `RenameStationForm.validate` checks three things: not blank, not `-`, unique.
   A space is accepted.

   `SurvexExporter` then writes station names into whitespace-separated columns, so that leg comes
   out as

   ```
   1	sump 2	5.000	10.00	0.00
   ```

   which is six fields where `*data normal from to tape compass clino` wants five. Survex will not
   read it. A **semicolon** is worse than a space: it begins a comment in Survex, so `sump;2` throws
   the whole reading away and the file still parses — a leg quietly gone rather than an error. A tab
   inside a name shifts the columns of a tab-separated file. Therion separates its columns the same
   way.

   Nothing warns, at either end. The export screen shows a file that looks plausible; the failure
   happens on somebody's laptop, hours later, after the trip.

   This port refuses those names when a station is renamed, and says which character and why —
   the one place a divergence is worth it, because the alternative is losing a trip's numbers in
   silence. It does *not* rewrite an imported name: this app cannot repair somebody else's file by
   refusing to open it, and a station silently renamed on export is a station nobody can match back
   to their notes. So it stops the problem being made rather than pretending it cannot exist.

48. **A preference that is only a `var` is not a preference, and dark mode was one.**
   This port's own. `DemoState.darkMode` was a `mutableStateOf(false)` flipped straight from a
   checkbox on the menu, so it came back light on every run — and the Android app it was copied
   from has `pref_theme`, a three-value `ListPreference` (auto, light, dark) applied through
   `AppCompatDelegate.setDefaultNightMode`, defaulting to *auto*.

   Two things were wrong and only one of them looks like a bug. The obvious one is that the
   setting went nowhere — which is finding 32 again, in a sixth place, for the same reason: the
   value was reached through `DemoState` rather than through `AppPreferences`, so nothing about
   `state.darkMode = !state.darkMode` suggests a missing file write. Finding 32 was five toggles
   found by comparing the menu against `drawing.xml`; this one survived that sweep because it is
   not on that menu. **A value held on the state object and a value held in the preferences file
   are indistinguishable at every call site**, so the only reliable way to find these is to ask
   of each setting, one at a time, whether closing the app would lose it — which is what turned
   this one up. `darkMode` is now a computed property with no setter, so the shape that caused
   it twice cannot recur here a third time.

   The less obvious one is that a checkbox was the wrong control. *Automatic* on a phone answers
   "is it evening"; it has never been asked "am I underground", and a cave is dark at noon. A
   two-state toggle can be set to dark, but there is then no way back to following the phone —
   and the surveyor who wants that is the one who leaves the app open on the walk out.

   Why it matters more here than the wording suggests: a phone is the brightest object in a cave,
   the OS kills a backgrounded app while it is in a pocket between stations, and the surveyor
   reopening it got a full-brightness white page in the face — after which their dark adaptation
   is gone for a quarter of an hour, on the trip where seeing the passage is the job.

   The check that would have caught it is the one now in `field.mjs`, and it is the reload that
   gives it teeth: choosing Dark and measuring the screen passes with the bug live. It has to
   close the page and come back.

49. **Asking finding 48's question of every setting found four more, and one of them changes what
   the numbers mean.** Finding 48 ended by saying that the only reliable way to find a lost
   setting is to go through them one at a time and ask whether closing the app would lose it. Done
   properly, that question found four: `inputMode`, and the three `SketchPreferences` string keys
   `pref_sketch_sketch_tool`, `pref_sketch_brush_colour` and `pref_sketch_symbol`. All four are
   persisted in the Android app. All four were plain `var`s here.

   Three of them are conveniences — reopening on the eraser rather than the pan tool, or on black
   rather than the blue you were drawing a stream in. **`inputMode` is not.** It decides whether a
   shot is read from the current station towards the next one or standing at the next one looking
   back, and `SurveyUpdater` reverses the leg before hanging it on the tree when it is
   `BACKWARD`. A surveyor working back out of a passage on backsights, whose phone is killed in a
   pocket between stations — which is the ordinary case, not the unlucky one — reopened the app on
   foresights.

   What makes it worse than a lost preference is the direction the failure points. This port's own
   field bar shows the mode **only when it is not FORWARD**, which is the right design and exactly
   the wrong thing here: the state the app wrongly came back in is the state that displays
   nothing. The surveyor sees a normal screen, carries on, and every leg from there is a hundred
   and eighty degrees out. Nothing in the readings can show it: a backsight and a foresight down
   the same passage are both perfectly ordinary numbers.

   Two smaller things fell out of doing it properly:

   - **Not every tool is worth restoring.** Five of the eleven are armed for one gesture or one
     tap — a pinch, a hot-corner pan, the three cross-section drags. An app that opened with *the
     next touch drops a cross-section* still armed would drop one under the surveyor's first
     touch. So only the toolbar's own six come back. The Android app has the same hole and does
     not fall into it, because its `setSelectedSketchTool` is only reached from its toolbar
     handler; this port sets the tool directly for the cross-section gestures, so the rule had to
     be written down rather than assumed.
   - **`SketchPreferences` reads its three through `valueOf`, which throws.** A file naming a tool
     that a later version dropped would throw on the way into the sketch screen rather than fall
     back. Small, and upstream, and not worth a report on its own — but nothing about a preference
     should be able to stop the app opening a survey, so this port reads all four by name with a
     fallback.

   The test is the shape that matters more than the finding. Checking that a value reached the
   file is the half that was never in doubt; `AppPreferencesTest` builds a second `DemoState` over
   the same in-memory store, which is the reading half as well — the thing a surveyor actually
   does when the OS kills the app in their pocket. Run against the `var` put back, it fails.

50. **A callback wired up once, and the object holding it rebuilt every time a survey is
   opened.** This port's own, and the one that has been broken for longest without anyone
   noticing. `DemoState.loadSettings` sets `session.onStationCreated = ::noteStationCreated`, and
   it runs once, when the app opens. Every route to a survey the surveyor actually cares about —
   *New*, *Open*, *Import*, and deleting the open one — goes through `adopt`, which builds a
   **fresh** `SurveySession`. A fresh session has no callback.

   So the buzz when a station is made worked on the demo cave and stopped the moment somebody
   made a survey of their own, which is every real use of the app. And so did *Follow the survey*,
   because the counter it watches is incremented by the same callback: the view stops re-centring
   at exactly the point the surveyor starts needing it to.

   Two things about how it hid:

   - **It is invisible in a diff.** `loadSettings` and `adopt` are two hundred lines apart and
     neither mentions the other. Nothing about `session = SurveySession(survey)` says that four
     things hanging off the old session have just been dropped. This is the third finding in a row
     — with 48 and 49 — whose whole cause is that a *connection* between two objects is not
     visible where either of them is written.
   - **The browser check passed anyway.** `field.mjs` has a check that the active station lands
     within forty pixels of the middle of the screen after a leg goes in, deliberately written to
     assert *where the view ended up* rather than that the screen changed. It has been green
     through every run with this defect live — because the canvas fits the whole survey to the
     screen when it is not following, and on a small survey that puts the newest station near the
     middle anyway. A check can assert exactly the right thing and still not discriminate, if the
     fixture is small enough that both behaviours look alike. Same lesson as finding 42, from the
     opposite direction: there the fixture could not express the failure, here it cannot
     distinguish the fix.

   The test that does discriminate takes two lines and is at the level the bug lives at: make a
   survey through `DemoState`, take three readings, and count the stations the *app* noticed. It
   fails against the code as it was.

51. **A clock that only ran while its own dialog was open.** This port's own, found while wiring
   up the reconnection policy and worth writing down separately, because it is the reason the
   policy could not have worked whatever the policy said.

   `SurveySession.tick` ages out a connection attempt — `GattSession.tick` is what turns *waiting*
   into "the instrument is off or out of range" — and it was driven by a `LaunchedEffect` inside
   the connection dialog and another inside the calibration dialog. Which means: a surveyor who
   pressed Connect and then closed the dialog left an attempt that could never time out, with the
   radio scanning until they went back and looked. On an iPhone that is a battery cost on the one
   device with the survey on it.

   And it makes auto-reconnect impossible by construction. A surveyor waiting for an instrument to
   come back is **drawing**, not sitting on the connection screen. A retry loop that only runs on
   a screen nobody is looking at is a retry loop that never runs.

   The general shape, which is worth more than the instance: **work that has to happen while the
   app is *doing* something must not be owned by the screen that configures it.** One loop in
   `App`, keyed on whether an instrument is attached, costs nothing when there is not one and runs
   everywhere when there is.

52. **Two sketching preferences that do nothing, and four more that show one number and use
   another.** Upstream, found by reading `preferences_sketching.xml` against
   `GeneralPreferences` in order to port the group.

   **`pref_survey_text_tool_font_size` is inert.** The settings screen writes that key.
   `GeneralPreferences.getTextStartingSizeSp`, which is what `GraphView` asks for when the text
   tool places a label, reads **`pref_survey_text_tool_font_size_sp`** — a different key, with an
   `_sp` on the end. Nothing writes that one. So the preference can be set to anything and the app
   goes on using 16, and the screen offers 50 as its default, which is more than three times the
   number actually used. Grepped both spellings across `app/src`: the screen's appears once, in the
   XML; the getter's appears once, in the Java. They never meet.

   **`pref_label_font_size_sp` is unreachable**, in the other direction: `getLabelFontSizeSp` reads
   it — it is the size of the text a surveyor writes on the sketch — and it is on no preference
   screen at all, so it can never be anything but 12.

   And four preferences show a default the code does not use, because nothing calls
   `PreferenceManager.setDefaultValues` and the getter's own fallback wins on a fresh install:

   | Preference | The screen says | The app uses |
   | --- | --- | --- |
   | `pref_station_label_font_size_sp` | 8 | 10 |
   | `pref_legend_font_size_sp` | 8 | 10 |
   | `pref_survey_symbol_size` | 35 | 25 |
   | `pref_anti_alias` | unticked | **on** |

   The last is the same defect as `pref_vibrate_on_new_station`, which this document already
   records: the box shows unticked while the behaviour is on, and toggling it twice is what makes
   the screen tell the truth. Which is the point worth making — this is not four coincidences, it
   is one missing call, and every preference on that screen has it.

   This port takes the values the app **draws with** in every case, because those are what a
   surveyor is actually looking at, and `SketchStyleTest` asserts each of the four against the
   getter rather than against the XML so the divergence is written down where somebody changing it
   will meet it.

53. **A rule the engine implemented and the app never offered.** This port's own, and the mildest
   of the recent run, but the same shape as finding 40 and worth counting because of that.
   `SketchEditor.eraseAt` has taken a `deletePathFragments` flag since the sketch was ported — it
   is what decides whether rubbing out the middle of a passage wall leaves both ends or deletes the
   whole stroke — and it is `pref_delete_path_fragments` on the Android sketching screen. The
   canvas never passed it. So the port had the *behaviour*, correctly, and not the *choice*.

   The general form: **a defaulted parameter is where a feature goes to hide.**
   `eraseAt(..., deletePathFragments: Boolean = true, ...)` reads at the call site as though the
   caller has considered it and chosen the default; nothing marks the difference between a default
   that was decided and one that was never thought about. Which is findings 48 and 49 again in a
   third disguise — those hid behind a `var` that looked like a stored one, this behind a parameter
   that looked like a passed one — and the same question finds all three: *what would a surveyor
   have to do to change this, and can they?*

54. **The first time this ran on a real phone, it crashed on something no test here could have
   caught.** Reported from a device: *"On iOS I get an error when clicking on the menu in the top
   right"*, with a disassembly whose only readable frames were `kotlin#error` inlined at
   `Preconditions.kt:145` and `PlistSanityCheck.uikit.kt`.

   `PlistSanityCheck` is Compose Multiplatform's, not this port's. It reads
   `CADisableMinimumFrameDurationOnPhone` out of `Info.plist` and calls `error()` when the key is
   missing or false — the message says so, and the key was simply absent here.

   Three things about it are worth keeping:

   - **The symptom named the wrong thing.** The check runs inside
     `dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_LOW, ...))`. A low-priority
     global queue does not get CPU while the main thread is busy drawing a first frame, so the
     throw lands later — at the next quiet moment, which is *after* a tap. The overflow menu had
     nothing to do with it, and a whole afternoon could have gone into the menu code.
   - **Nothing in this repository mentioned the key.** `IosAssetsTest` already checked the plist,
     and checked the right things — the Bluetooth usage description, the file-sharing pair — all
     of them requirements of *Apple's*. This is a requirement of a **dependency**, and the general
     form is worth writing down: **a manifest can satisfy the platform and still fail the
     framework**, and the framework's requirements are not discoverable from the platform's
     documentation. The fix for the class, not the instance, is that a plist check should be
     derived from what the app's dependencies demand and not only from what iOS demands.
   - **It is the first thing real hardware found, and CI did not see it — and I do not know why.**
     This bullet said, until it was checked, that the check is gated to devices and so cannot fire
     on a simulator. That was invented. `ComposeHostingViewController.viewDidLoad` calls
     `PlistSanityCheck.performIfNeeded()` unconditionally whenever
     `enforceStrictPlistSanityCheck` is set, which it is by default, and nothing in either that
     call site or the check itself mentions a simulator. So CI's simulator run *should* have died
     the same way, and it did not.

     The honest state of it: I have a mechanism for the symptom on the phone — the throw is
     `dispatch_async`'d to `DISPATCH_QUEUE_PRIORITY_LOW`, so it lands whenever that queue next gets
     CPU — and no verified explanation for why the same build survived twenty-five seconds on a
     CI simulator. The most likely candidate is that the low-priority block was simply starved on
     a runner that was busy booting, installing and launching, and that the screenshot was taken
     of an app that had not died *yet*; **CI never looks at it again.** That is a hole in the check
     whatever the answer, and it is now closed — the run takes a second look later, and scans the
     host's crash reports for this bundle.

     Which leaves the lesson pointing somewhere better than where I first put it. It is not
     *"only a phone can find this"*: it is **a check that looks once cannot see anything that
     happens afterwards**, and tonight's bug happens afterwards by construction. The device found
     it first because a person kept using the app; CI stopped watching after one photograph.

   The key is also correct on merit, which is the smaller half: without it iOS caps the display
   link at 60 Hz, so the sketch would pan at half the rate a 120 Hz iPhone can manage.
   `IosAssetsTest` now asserts the key is present **and true** — a key set to `false` fails
   Compose's `boolValue != true` in exactly the same way as a missing one — and both failure modes
   were run against the test before it was written down.

55. **Half a degree, in the wrong direction, and no way to type the right one.** Upstream, found
   porting degrees-and-minutes entry and confirmed by *running* the Java rather than reading it.

   `EditLegForm.getInclination` builds a signed angle out of three fields:

   ```java
   float sign = degrees < 0 ? -1.0f : 1.0f;
   return degrees + sign * (minutes / 60f + seconds / 3600f);
   ```

   with a comment saying minutes and seconds are always positive and take their direction from the
   degrees. That is right for every input but one. A shot **less than a degree below horizontal**
   has degrees `-0`, and `Float.parseFloat("-0")` is negative zero, and `-0.0f < 0` is **false** by
   IEEE 754. Compiled and run: `parsed=-0.0 sign=1.0 result=0.5`. So 0° 30′ *down* is stored as
   0° 30′ *up* — and since the minutes field is documented as always positive, **there is no other
   way to express that angle in that mode at all.**

   Why it is worth a line rather than a shrug. Half a degree is nothing; half a degree *the wrong
   way* is a metre of depth over two hundred, and nearly-horizontal shots are not a corner case —
   they are what a level passage is made of. Nothing in the numbers afterwards says which was
   meant, because a shot half a degree up and one half a degree down are both perfectly ordinary
   readings.

   The fix is one line and it is the same lesson as `withSignFlipped`, which this port already had
   for the decimal field: **take the sign from the text, not from the parsed number.** A minus sign
   is a character a surveyor typed; a float cannot carry it once the magnitude is zero.
   `DegreesMinutesSeconds` does that, and its `Parts` keeps `negative` as its own flag rather than
   signing the degrees, so the same value survives being written back into the box.

56. **The one place foreign data meets the main thread.** This port's own, and the reason for
   looking was finding 54: a phone had just shown that an uncaught throw on a background queue
   ends the app, so the question worth asking was where else one could come from.

   Every byte from an instrument arrives at `SurveySession`'s `onFrame`, on the platform's
   callback thread, and is handed straight to a decoder. Those decoders are careful — each one
   checks the length before it reads, and `Sap6Protocol.decode`'s `require` is unreachable because
   `Sap6Decoder` guards it — but *careful against the protocol as documented* is the most that can
   be said, and the instruments this port has never met are precisely the ones likely to send
   something the documentation does not cover.

   The asymmetry is the point. **A packet that cannot be read costs one shot; a crash costs the
   connection, the screen, and the surveyor's confidence in the thing holding their trip** — in a
   cave, with cold hands, at the far end. The survey is written on every change, so nothing already
   recorded is lost either way; what differs is everything after. So a throw here is now a line in
   the log the surveyor can already read and copy off the phone, which is the whole reason that
   log exists, and the tick that drives the connection state machine is wrapped for the same
   reason.

   The rest of the sweep found nothing to fix, which is worth saying rather than padding: the two
   `!!` in the UI are both guarded by the condition that made them non-null; the loaders already
   return null and report rather than throwing; `Survey`'s tree walk already carries a `seen` set,
   so the cycle of finding 19 is a refusal rather than a hang; and the iOS actuals hold no forced
   unwrap of an Objective-C nullable — which matters more than it sounds, because an Objective-C
   exception on Kotlin/Native is not catchable at all, so the only defence against those is not
   provoking them.

---

## A defect worth reporting upstream

The Android app's **PocketTopo export is not reproducible**. `Space` keys its station and leg maps
on `Station` and `Leg`, neither of which overrides `hashCode`, so iteration follows identity hash
codes and changes between runs. Building one survey twice in a single JVM and exporting both gave
the STATIONS and SHOTS lines in completely different orders.

Nobody can depend on an order that is undefined, so this port picks a defined one - stations and
legs in the order they were surveyed. That makes an export diffable, reviewable and testable, none
of which the original's are. It is the only deliberate divergence in that exporter; everything the
Java defines is reproduced exactly, including two things that look like mistakes and are left for
the maintainer to judge:

- the y of a *sketch* point is negated and the y of a *station* is not, which should leave the
  drawing mirrored against the centreline it belongs to;
- a survey with no trip gets a blank line after the date and a survey with a trip does not.

The same class of bug is in the Compass exporter, differently: its splay counter resets whenever
the from-station changes, so a surveyor who shoots splays off a station, moves on, and later
returns gets a second run numbered from zero and two splays sharing a name.

A third, found porting the fade: **which stations come out faded depends on hash order.**
`GraphView.drawStations` sets its paint to a fifth alpha, walks the station map, and sets the alpha
back to solid when it reaches the active station — and never sets it down again. So every station
that happens to be iterated *after* the active one is drawn solid too, and which those are is
`Space`'s `HashMap` order over `Station`, which does not override `hashCode`. Turn the fade on
twice in one run and the same cave can fade differently. The fix is one line — ask the question per
station rather than carrying the answer between them — and this port asks it per station.

A second one, found writing the leg menu and cheaper to fix: **a comment typed on a leg or a
station is not saved unless something else is changed too.** `SurveyEditorActivity`'s two comment
dialogs set the comment and broadcast an update, and neither clears `isSaved` — and `isSaved` is
what decides whether leaving the survey writes it out and whether the app asks before discarding
it. So a surveyor who stops at a junction, writes "sump; do not follow" against the leg ahead, and
changes nothing else, loses it. It is the quietest possible way to lose the one note that mattered.
Both paths here set `isSaved` — `applyLegComment` and `applyStationEdit` — with a test each saying
why.

And a fourth, the cheapest of the lot to fix and the easiest to miss: **a preference on the
sketching screen is wired to nothing.** The screen writes `pref_survey_text_tool_font_size`;
`GeneralPreferences.getTextStartingSizeSp` reads `pref_survey_text_tool_font_size_sp`. One key has
an `_sp` the other has not, each spelling appears exactly once in the whole app, and they never
meet — so the text tool's size can be set to anything and the app goes on placing labels at 16.
`pref_label_font_size_sp` is the same fault mirrored: read by `getLabelFontSizeSp` and on no screen
at all. Both are a one-word change. Written up in full, with the four preferences whose screen
default and code default disagree, as finding 52.

And a fifth, one line long and quietly wrong in the direction that matters: **a shot less than a
degree below horizontal is stored as one above it.** `EditLegForm.getInclination` takes its sign
from `degrees < 0`, and `Float.parseFloat("-0") < 0` is false — compiled and run, it prints
`parsed=-0.0 sign=1.0 result=0.5`. So 0° 30′ down becomes 0° 30′ up, and because the minutes field
is documented as always positive there is no other way to type that angle. Take the sign from the
typed text rather than from the parsed number. Finding 55.

---

## A portability trap worth knowing about

`Float.toString()` is not the same function on every Kotlin target. Java - and so Kotlin/JVM, and so
the Android app - switches to scientific notation outside roughly 1e-3..1e7. Kotlin/Wasm does not:

| value | JVM | Wasm |
| --- | --- | --- |
| `1e-5f` | `1.0E-5` | `0.00001` |
| `1e7f` | `1.0E7` | `10000000.0` |

Ordinary survey magnitudes agree, so this is an edge case, but a real one. Two consequences, both
pinned by `FloatRenderingTest`:

- **The exporters are safe.** Every number in a Survex, Therion or Compass file goes through
  `formatFixed`, which builds its output from integers and never calls `Float.toString`. Those
  formats are byte-identical across targets.
- **The JSON is value-safe but not byte-safe.** The survey and sketch writers hand raw `Float`s to
  kotlinx.serialization, so an extreme coordinate can be spelled differently on different
  platforms. Both spellings are valid JSON for the same number and the Android app parses with
  `getDouble`, so nothing is lost - but the file is not byte-identical.

Making the JSON byte-identical too would mean reimplementing Java's shortest-round-trip
`Float.toString`. That is real work for a case no surveyor will hit, so it is documented and
guarded rather than fixed. Worth knowing before somebody diffs a survey written on an iPhone
against the same survey written on Android and concludes the port is broken.

---

## The browser target

The whole app runs in a browser, and this is the strongest single piece of evidence on the branch:
the ported survey core, the shared Compose UI and Skia, compiled to WebAssembly and running
somewhere with no `java.*` in it at all. Not a screenshot of a survey — the actual app, which you
can draw on.

![browser](docs/images/browser-drawn.png)

An earlier revision of this file said the page did not render, because it did not: Skia ships no
system fonts on the web, so every text draw threw and the page came up blank. Bundling Liberation
Sans fixed it. That claim then sat here as prose, stale and wrong, which is exactly why it is now a
CI job instead — `kmp/demo/browser-test/smoke.mjs` loads the page in headless Chromium and fails if
it throws, if the canvas never attaches, if the page renders blank, if a resource 404s, or if
drawing a stroke and undoing it does not change what is on screen.

```bash
cd kmp && ./gradlew :demo:wasmJsBrowserDistribution
cd demo/browser-test && npm install && npx playwright install chromium
(cd ../build/dist/wasmJs/productionExecutable && python3 -m http.server 8731 &)
node smoke.mjs http://localhost:8731/index.html screenshots
```

This is also the easiest way to show somebody the demo: it needs no Xcode, no Android SDK and no
JVM — just a static file host.

---

## Where this is, and what would come next

Written down here rather than left in a commit log, because the useful thing to know on picking
this up again is which of the remaining items are *blocked* and which are merely *not done*.

**The state of it.** Everything in the evidence table above is on this branch and green in CI: 733
shared tests on three targets, 319 over the UI's own logic, 18 running the iOS half in a simulator,
94 browser checks driving the real page on a 420-pixel screen and finishing at 375x667 and then
667x375. The
Android app is untouched. Nothing here is half-finished in a way that would embarrass a demo — the
things that are missing are missing on purpose and are listed below.

**Blocked on something that is not code.**

1. **An instrument.** Both transports are written and both are driven end to end against a fake
   one; neither has met a radio, and the iOS simulator has no Bluetooth stack, so no amount of work
   here changes that. Longest lead time of anything: worth starting to borrow one now.
2. **A Mac with a phone plugged into it.** Somebody has now pressed Run on real hardware, and the
   first thing it found was a crash nothing here could reach — see finding 54. That is the honest
   summary of this whole line item: the build instructions were right, the app launched, and one
   `Info.plist` key belonging to Compose rather than to Apple took it down on a phone and on no
   simulator. What is still unmet is a dialog with the *keyboard up*, which takes a third of an
   iPhone SE's screen and which no headless browser and no simulator screenshot has. The dp conversion (finding 28) was on this list and is
   not any more — `DrawingDensityTest` renders the canvas at a phone's density and would catch it —
   and the app icon has been through a real `actool`.
3. **A decision from upstream.** Cross-survey links are absolute `content://` URIs; replacing them
   is a format question, not a porting one. And GPL-3.0 on the App Store needs a Section 7 exception
   from every copyright holder, which gates release rather than development and takes as long as it
   takes.

**Not done, and nothing is stopping it.**

- **The compass *swinging*.** The arrow is drawn, it has its own toggle, and on a plan north
  genuinely is up — `Projection2D.PLAN` maps the northing to minus the screen y — so a fixed one is
  correct rather than approximate. What is missing is the magnetometer that turns it as the phone
  turns: an `expect`/`actual` on three platforms and, on iOS, a usage-description key that crashes
  the app on launch if it is wrong.
- **Drawing less of a heavily traced drawing.** With a fully traced cave *all on screen*, eight
  thousand strokes are 120 ms a frame in the headless renderer. Culling does not touch it — they
  are genuinely visible — so it would want level of detail, which changes what a surveyor sees and
  is a decision rather than a fix. Measured, not guessed: see finding 29.

**The one that matters most, and it is not iOS.** Pointing the Android app at this shared core makes
the work pay for itself whether or not an iPhone ever runs it, and brings the stack-overflow and
quadratic-export fixes with it. It is deliberately not attempted here: it is a conversation to have
before it is a branch to write.

---

## Deliberate gaps

This is a proof of concept. File formats are no longer one of the gaps: every importer and every
exporter the Android app has is ported. In come Survex, Therion, PocketTopo `.txt` and PocketTopo's
binary `.top`; out go Survex, a whole Therion project — `.thconfig`, `.th` and a `.th2` and `.xvi`
per drawing, each naming the next — Compass `.dat`, PocketTopo `.txt`, SVG and the native JSON.

What it does **not** include:

- **Real Bluetooth on any platform.** `CoreBluetoothTransport` and `WebBluetoothTransport` are
  both written, both reachable from the app, and both driven end to end against a *fake*
  instrument — but neither has met a radio. The iOS simulator has no Bluetooth stack, so this one
  genuinely needs an instrument in hand. There is no Android transport here either (the Android app
  keeps its own).
- **Cross-survey links.** They live in `Name.metadata.json`, the fourth file of a survey, and this
  port **does not read or write that file at all** — so a survey imported here and exported again
  comes back without its links. Worth saying precisely, because the obvious half-measure is worse
  than the loss: carrying the file through untouched would also carry its `active-station`, and the
  Android loader reads that *after* the data file and would therefore override the station a
  surveyor had just been working at. Doing it properly means parsing metadata to update one field,
  and the links themselves are absolute `content://` URIs — meaningless off Android, and already
  broken when a folder moves — so it is a format decision to take with upstream rather than a
  porting one. Nothing here draws a neighbouring survey either.
- **The Android app adopting this core.** That is the step that would make the work pay for itself
  regardless of the iOS outcome, and it is deliberately not attempted yet.

---

## If this were taken further

Roughly the order that keeps every intermediate state shippable:

1. **Agree the direction first.** A restructure like this only works upstream; as a fork it dies.
2. **Licensing, in parallel and early.** GPL-3.0 on the App Store needs a Section 7 "App Store
   exception" from every copyright holder — Rich, the eight named contributors, and Beat Heeb for
   the calibration algorithm. It gates release, not development, so it should start first. The
   *About* box now names all of them and carries the licence, which is the minimum the GPL asks of
   an interactive program and which this port had gone without for its whole life.
3. Gate the exporters — all of them are here now — on byte-identical output against the existing
   `exportTherionFixtures` / `exportSvgFixtures` golden bundles, which is a stronger check than the
   hand-written goldens in this branch.
4. **Point the Android app at the shared core and ship it.** After this the effort has paid for
   itself even if iOS never happens.
5. Then the iOS-specific work. "Compile it and fix what the first build finds" is **done** — it cost
   about three hours once somebody noticed that GitHub gives public repositories free macOS runners.
   What is left is file handling, a device build, and CoreBluetooth against a real instrument.

Two things should be measured on real hardware before committing to any of it: sketching latency
with an Apple Pencil on an iPad, and a CoreBluetooth connection to an actual instrument. Start
borrowing instruments now rather than later — all five profiles here are unverified transcriptions,
and it is the item with the longest lead time because it is procurement, not engineering.

And one thing to tell existing users early: the original **DistoX** and **DistoX2** speak Bluetooth
Classic RFCOMM, for which Apple exposes no public API. They can never work on iOS. Every instrument
in this port is BLE and needs no Apple certification, but those two are permanently Android-only.

---

## Provenance

Forked from [richsmith/sexytopo](https://github.com/richsmith/sexytopo). GPL-3.0, like the original
— a transliteration is a derivative work, so this code carries the same licence. Liberation Sans is
bundled under the SIL Open Font License 1.1; see `demo/LICENSE-LiberationSans.txt`.
