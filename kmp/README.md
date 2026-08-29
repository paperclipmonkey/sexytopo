# SexyTopo on iOS — a Kotlin Multiplatform proof of concept

This directory is an **experiment**, not a product, and not a proposal to change the Android app
yet. It exists to answer one question with running code rather than argument:

> If SexyTopo's survey core were shared Kotlin instead of Android Java, would the same code
> actually drive an iOS app?

So far the answer is **yes for everything except the parts that need a Mac to check**. The survey
engine, the instrument protocols, the projection maths, the sketch model and the native file format
are ported and covered by 226 tests. The UI — including a drawable sketching canvas — is written
once in Compose Multiplatform and renders through Skia, which is what Compose uses on iOS.

Nothing in the existing Android app has been touched. `kmp/` is a separate Gradle build alongside
it, which does not even need the Android SDK.

---

## What is actually proven, and what is not

Being precise about this matters more than the demo looking good.

| Claim | Status | Evidence |
| --- | --- | --- |
| Survey model, projection maths and the extended-elevation unroll port to Kotlin | **Verified** | tests, on two targets |
| The survey engine builds stations from readings the way the app does | **Verified** | `SurveyUpdaterTest` (55 tests), triple-shot promotion and all three amalgamation algorithms |
| Instrument packets decode identically | **Verified** | byte-level tests for DistoX, DistoX-BLE, BRIC, SAP6, Cavway, FCL |
| The whole chain works end to end | **Verified** | `SurveyingEndToEndTest`: simulated instrument → packet decode → station promotion → JSON round-trip |
| Native JSON survey/sketch formats read and written compatibly | **Verified** | round-trip tests against Android-shaped fixtures |
| Shared Compose UI draws, and can be drawn on | **Verified** | `./gradlew :demo:renderDemoPng`; drawing/erasing/undo covered by tests |
| **The shared core has no JVM-only dependencies** | **Verified** | all 202 shared tests pass on **Kotlin/Wasm** as well as the JVM |
| The same code compiles for iOS | **Not verified** | Kotlin/Native for Apple targets needs macOS; this was authored on Linux |
| The iOS app runs on a device | **Not verified** | needs Xcode |
| `CoreBluetoothTransport` works | **Not verified** | written, never compiled — see its KDoc |
| Browser demo | **Builds and tests pass; the page does not render** | see "The browser target" below |

**The honest summary:** everything checkable without a Mac has been checked and passes, and the
Kotlin/Wasm run is a meaningful proxy for the iOS build — Kotlin/Wasm, like Kotlin/Native for iOS,
has no `java.*` at all, so a green run there means the core is not quietly leaning on the JVM. What
remains genuinely unverified is Apple-specific: the Kotlin/Native compile, Xcode, and CoreBluetooth
against real hardware. **Expect to fix something on the first real build.**

---

## What the demo does

![plan](docs/images/plan.png)

- **Two surveys.** A generated demo cave, and a **live survey** you build yourself.
- **Live surveying.** "Take reading" makes the simulated instrument emit a real DistoX wire-format
  packet; the ported protocol decodes it; the ported engine promotes three agreeing readings into a
  station. That is the core interaction of the whole app, and only the radio is pretend.
- **Sketching.** Draw, erase and pan tools, six brush colours, undo/redo. Strokes are captured in
  survey metres (never pixels) and simplified on release. Erasing splits a stroke rather than
  deleting it, so rubbing out the middle of a passage wall leaves both ends.
- **Plan and extended elevation**, the latter exercising the cave-unrolling maths.
- **The survey table**, with backwards shots normalised back to the reading as taken.
- Light and dark, and a layout that collapses to one scrollable toolbar on a phone.

| Extended elevation | Live survey from the instrument | Table |
| --- | --- | --- |
| ![elevation](docs/images/extended-elevation.png) | ![live](docs/images/live-survey.png) | ![table](docs/images/table.png) |

---

## Building

Everything below works on Linux/macOS/Windows except where noted.

```bash
cd kmp

./gradlew :shared:jvmTest          # the ported test suite (202)
./gradlew :shared:wasmJsNodeTest   # the same tests on a NON-JVM target
./gradlew :demo:jvmTest            # the interactive drawing layer (24)
./gradlew :demo:renderDemoPng      # render the shared UI to PNGs, no display needed
./gradlew :demo:run                # the desktop app (needs a display)
```

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
| `control/graph/GraphView` (2,199 lines) | `demo/.../SurveyCanvas.kt` | **Rewritten**, not ported |
| `control/util/GraphToListTranslator` | `demo/.../SurveyTableView.kt` | Including as-taken normalisation |

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
   byte-tested; the platform transport is ~270 lines. That was the feasibility study's central claim
   about this layer and it holds up.

---

## The browser target

`:demo:wasmJsBrowserDistribution` builds, and the shared tests pass on Kotlin/Wasm under Node — the
target is genuinely useful for that reason alone. But **the browser page does not render**: it
throws during startup inside Compose Resources' font loading. Bisection established the shape of it
(a pure-drawing canvas works; anything with `Text` throws; no font is fetched at all), and bundling
a font plus `configureWebResources` did not resolve it in this environment. It is left in place,
clearly marked, rather than removed: someone with a browser to poke at will likely find it quickly,
and the Wasm target earns its keep as a non-JVM test bed regardless.

---

## Deliberate gaps

This is a proof of concept. It does **not** include:

- **Real Bluetooth on any platform.** `CoreBluetoothTransport` is written but uncompiled; there is
  no Android transport here (the Android app keeps its own).
- **Calibration.** The DistoX calibration solver is pure maths and would port directly; it is not
  done.
- **Symbol artwork, cross-sections, text and select tools.** The canvas draws and erases lines.
- **The exporters** — Therion, Survex, Compass, PocketTopo, SVG, XVI.
- **The rest of the Android UI**: trip metadata, settings, stats, the 3D view, the manual.
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
3. Extend the shared core to the exporters, gated on byte-identical output against the existing
   `exportTherionFixtures` / `exportSvgFixtures` golden bundles.
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
