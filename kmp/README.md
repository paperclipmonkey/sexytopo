# SexyTopo on iOS — a Kotlin Multiplatform proof of concept

This directory is an **experiment**, not a product, and not a proposal to change the Android app
yet. It exists to answer one question with running code rather than argument:

> If SexyTopo's survey core were shared Kotlin instead of Android Java, would the same code
> actually drive an iOS app?

So far the answer is **yes for everything except the parts that need a Mac to check**. The survey
engine, the instrument protocols, the projection maths, the sketch model, the sketch *editor*, the
Survex and Therion exporters and the native file format are ported and covered by 372 tests. The UI
is written once in Compose Multiplatform and renders through Skia, which is what Compose uses on
iOS — and it drives the ported logic rather than reimplementing it, which is the part that actually
tests the claim.

Nothing in the existing Android app has been touched. `kmp/` is a separate Gradle build alongside
it; everything except the Android host builds without an Android SDK at all, which is the clearest
demonstration that the core no longer depends on one.

---

## What is actually proven, and what is not

Being precise about this matters more than the demo looking good.

| Claim | Status | Evidence |
| --- | --- | --- |
| Survey model, projection maths and the extended-elevation unroll port to Kotlin | **Verified** | tests, on two targets |
| The survey engine builds stations from readings the way the app does | **Verified** | `SurveyUpdaterTest` (55 tests), triple-shot promotion and all three amalgamation algorithms |
| Instrument packets decode identically | **Verified** | byte-level tests for DistoX, DistoX-BLE, BRIC, SAP6, Cavway, FCL |
| The whole chain works end to end | **Verified** | `SurveyingEndToEndTest`: simulated instrument → packet decode → station promotion → JSON round-trip |
| Native JSON survey/sketch formats read and written compatibly | **Verified** | round-trip tests against Android-shaped fixtures, including corrupt and old-format files |
| Survex and Therion export byte-identically | **Verified** | golden tests asserting the full file, metadata block included |
| The sketch editor — tools, viewport, hit-testing, undo — is platform-free | **Verified** | `shared/sketch/`, driven by the demo and tested on two targets |
| The BLE connection logic is platform-free | **Verified** | `GattLinkTest` and `GattSessionTest` — the profile matrix *and* the connection lifecycle; only callback plumbing is left in `iosMain` |
| Shared Compose UI draws, and can be drawn on | **Verified** | `./gradlew :demo:renderDemoPng`; drawing/erasing/undo covered by tests |
| **The shared core has no JVM-only dependencies** | **Verified** | all 343 shared tests pass on **Kotlin/Wasm** as well as the JVM |
| The same code compiles for iOS | **Not verified** | Kotlin/Native for Apple targets needs macOS; this was authored on Linux |
| The iOS app runs on a device | **Not verified** | needs Xcode |
| `CoreBluetoothTransport` works | **Not verified** | written, never compiled — but its logic now lives in `GattLink` and `GattSession`, which are; see its KDoc |
| The whole app runs in a browser | **Verified** | a headless-Chromium smoke test in CI loads the page, draws a stroke and undoes it |
| The same UI builds and packages for **Android** | **Verified** | `:androidApp:assembleDebug` in CI; the APK is a build artifact |

**The honest summary:** everything checkable without a Mac has been checked and passes, and the
Kotlin/Wasm run is a meaningful proxy for the iOS build — Kotlin/Wasm, like Kotlin/Native for iOS,
has no `java.*` at all, so a green run there means the core is not quietly leaning on the JVM. What
remains genuinely unverified is Apple-specific: the Kotlin/Native compile, Xcode, and CoreBluetooth
against real hardware. **Expect to fix something on the first real build.**

---

## One UI, four platforms

`App()` is one composable, and it is a deliberate copy of SexyTopo's own sketch screen — the layout
of `activity_graph.xml`, the green panels from `colors.xml`, and the app's own toolbar artwork
carried across as Compose resources.

| iPhone 15 Pro | Pixel 8 | The same, dark |
| --- | --- | --- |
| ![iphone](docs/images/iphone-draw.png) | ![android](docs/images/android-plan.png) | ![dark](docs/images/android-dark.png) |

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
- **Export** to Survex `.svx` and Therion `.th`, and to the app's own JSON — the same bytes the
  Android app would read back.
- **Plan and extended elevation**, the latter exercising the cave-unrolling maths.
- **The survey table**, with backwards shots normalised back to the reading as taken.
- Light and dark, and a layout that collapses to one scrollable toolbar on a phone.

| Extended elevation | Live survey from the instrument |
| --- | --- |
| ![elevation](docs/images/extended-elevation.png) | ![live](docs/images/live-survey.png) |

| Survey table | Export |
| --- | --- |
| ![table](docs/images/table.png) | ![export](docs/images/export.png) |

---

## Building

Everything below works on Linux/macOS/Windows except where noted.

```bash
cd kmp

./gradlew :shared:jvmTest          # the ported test suite (343)
./gradlew :shared:wasmJsNodeTest   # the same tests on a NON-JVM target
./gradlew :demo:jvmTest            # the UI's use of the shared editor (29)
./gradlew :demo:compileKotlinWasmJs      # the UI compiled for a non-JVM target
./gradlew :demo:renderDemoPng            # render the shared UI to PNGs, no display needed
./gradlew :demo:run                      # the desktop app (needs a display)
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

### Running on iOS (needs macOS + Xcode)

The Xcode project is **generated rather than committed**, because it was authored on Linux where a
hand-written `.pbxproj` cannot be opened or validated:

```bash
brew install xcodegen
cd kmp/iosApp && xcodegen && open iosApp.xcodeproj
```

The build runs `./gradlew :demo:embedAndSignAppleFrameworkForXcode` as a pre-build phase, which
compiles the Kotlin/Native framework and embeds it. `kmp/iosApp/project.yml` and the README section
below it describe a fully manual alternative if you would rather install nothing.

The entire iOS-specific surface is three files: `demo/src/iosMain/.../MainViewController.kt` (one
function), the two Swift files in `iosApp/`, and — when you want real instruments —
`shared/src/iosMain/.../CoreBluetoothTransport.kt`.

---

## How it maps to the Android app

| Android app (Java) | Here (Kotlin) | Notes |
| --- | --- | --- |
| `model/graph/*`, `model/survey/*` | `shared/model/` | Straight translation |
| `model/graph/Projection2D` | `shared/model/graph/Projection2D.kt` | Including the load-bearing y-flip |
| `control/util/Space{2,3}DUtils`, `Space3DTransformer(ForElevation)` | `shared/math/` | The extended-elevation unroll |
| `control/util/SurveyUpdater`, `amalgamation/*`, `StationNamer` | `shared/survey/` | Triple-shot promotion, all three amalgamation algorithms, real default tolerances |
| `model/sketch/*` | `shared/model/sketch/` | Needed no translation — see below |
| `model/sketch/Colour` (150 values) | `shared/model/sketch/Colour.kt` | **Generated** from the Java enum so values cannot drift |
| `comms/distox/*`, `distoxble/`, `bric4/`, `cavwayx1/`, `sap6/`, `fcl/` | `shared/comms/` | Protocol only, no transport |
| the `*Manager` classes' device knowledge | `shared/comms/InstrumentProfile.kt` | The BLE device matrix, as data |
| Nordic `BleManager` subclasses | `shared/iosMain/.../CoreBluetoothTransport.kt` | The whole iOS Bluetooth surface |
| `control/io/basic/*JsonTranslater` | `shared/io/` | Same tags, same tolerant two-pass load |
| `control/graph/GraphView` — tools, viewport, hit-testing | `shared/sketch/` | Ported; the demo drives it |
| `control/graph/GraphView` — drawing and touch plumbing | `demo/.../SurveyCanvas.kt` | **Rewritten**, not ported |
| `res/layout/activity_graph.xml` | `demo/.../App.kt`, `SketchToolbar.kt` | The 9x2 toolbar, copied |
| `res/values/colors.xml` (+ `values-night`) | `demo/.../SexyTopoTheme.kt` | The app's own palette |
| `res/drawable-hdpi/*.png` | `demo/src/commonMain/composeResources/drawable/` | The app's own icons |
| `res/menu/drawing.xml` | `demo/.../SketchToolbar.kt` | The display toggles behind the gear |
| `model/sketch/Sketch`'s twin history stacks | `shared/sketch/SketchEditor.kt` | `DeletedDetail` becomes a sealed type |
| `control/io/thirdparty/{survex,therion,survextherion}` | `shared/io/export/` | Golden-tested, metadata block included |
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

8. **The specific bug worth quoting.** The first
   `CoreBluetoothTransport` compared `CBUUID.UUIDString` against the profile's 128-bit UUIDs as
   plain strings. Assigned-number UUIDs — which is what BRIC4 and BRIC5 use for *all four* of their
   characteristics — have a 16-bit short form, and CoreBluetooth reports whichever width the UUID
   actually has, so none of BRIC's characteristics would have matched: the instrument would pair
   and then do nothing. Android's `UUID.toString()` is always 128-bit, which is why the original
   never had to think about it. Nobody would have found this without a Mac, a BRIC and a cave.

8. **The Java loader silently turns corruption into data loss.** A leg naming a station missing
   from the file becomes a *splay* if you resolve the name leniently, which detaches every station
   beyond it with no error — the port did exactly this until a review caught it. Saving a sketch
   also dropped every cross-section. Both are the worst failure mode a survey app has: the file
   still opens, and what is missing is a branch of the cave. Anything reimplementing this format
   should start from the tests in `SurveyLoaderFidelityTest`.

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

## Deliberate gaps

This is a proof of concept. It does **not** include:

- **Real Bluetooth on any platform.** `CoreBluetoothTransport` is written but uncompiled; there is
  no Android transport here (the Android app keeps its own).
- **Calibration.** The DistoX calibration solver is pure maths and would port directly; it is not
  done.
- **Symbol artwork.** The shared model places symbols; the SVG assets are not carried, so a symbol
  draws as a marked point. The symbol, text and cross-section *tools* exist in the shared model but
  the demo has no palette, text field or cross-section editor to drive them.
- **The other exporters** — Compass, PocketTopo, SVG, XVI, and Therion's `.th2` sketch files.
  Survex and Therion `.th` are done and golden-tested.
- **The rest of the Android UI**: settings, stats, the 3D view, the manual.
- **The Android app adopting this core.** That is the step that would make the work pay for itself
  regardless of the iOS outcome, and it is deliberately not attempted yet — it also needs an Android
  SDK, which the machine this was written on did not have.

---

## If this were taken further

Roughly the order that keeps every intermediate state shippable:

1. **Agree the direction first.** A restructure like this only works upstream; as a fork it dies.
2. **Licensing, in parallel and early.** GPL-3.0 on the App Store needs a Section 7 "App Store
   exception" from every copyright holder — Rich, the eight named contributors, and Beat Heeb for
   the calibration algorithm. It gates release, not development, so it should start first.
3. Finish the exporters — Survex and Therion `.th` are done here; `.th2`, SVG, Compass and
   PocketTopo are not. Gate all of them on byte-identical output against the existing
   `exportTherionFixtures` / `exportSvgFixtures` golden bundles, which is a stronger check than the
   hand-written goldens in this branch.
4. **Point the Android app at the shared core and ship it.** After this the effort has paid for
   itself even if iOS never happens.
5. Then the iOS-specific work: compile it, fix what the first build finds, file handling, and
   CoreBluetooth against a real instrument.

Two things should be measured on real hardware before committing to any of it: sketching latency
with an Apple Pencil on an iPad, and a CoreBluetooth connection to an actual instrument.

---

## Provenance

Forked from [richsmith/sexytopo](https://github.com/richsmith/sexytopo). GPL-3.0, like the original
— a transliteration is a derivative work, so this code carries the same licence. Liberation Sans is
bundled under the SIL Open Font License 1.1; see `demo/LICENSE-LiberationSans.txt`.
