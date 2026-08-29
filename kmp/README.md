# SexyTopo on iOS — a Kotlin Multiplatform proof of concept

This directory is an **experiment**, not a product, and not a proposal to change the Android app
yet. It exists to answer one question with running code rather than argument:

> If SexyTopo's survey core were shared Kotlin instead of Android Java, would the same code
> actually drive an iOS app?

The answer so far is **yes for the core and the drawing surface**. The survey model, the projection
maths, and the native file format have been ported to Kotlin and are covered by tests that run on
every target. The survey canvas is written once in Compose Multiplatform and renders through Skia —
the same renderer Compose uses on iOS.

Nothing in the existing Android app has been touched. `kmp/` is a separate Gradle build that sits
alongside it.

---

## What is actually proven, and what is not

Being precise about this matters more than the demo looking good.

| Claim | Status | Evidence |
| --- | --- | --- |
| Survey model, projection maths and unroll port to Kotlin | **Verified** | 33 tests green on the JVM target |
| Native `.data.json` / sketch JSON read + written compatibly | **Verified** | Round-trip tests against Android-shaped fixtures |
| Shared Compose UI draws a real survey | **Verified** | `./gradlew :demo:renderDemoPng` renders PNGs headlessly via Skia |
| The same code compiles for iOS | **Not verified here** | iOS targets are declared and the framework is configured, but Kotlin/Native iOS compilation requires macOS. This was authored on Linux. |
| The iOS app runs on a device/simulator | **Not verified here** | Needs Xcode. See "Running on iOS" below. |
| Browser (Wasm) target | **Builds, does not run here** | `:demo:wasmJsBrowserDistribution` succeeds, but the app throws at startup in this container's headless Chromium and renders blank. Undiagnosed — it may well work on real hardware, but treat it as unproven. |

**The honest summary:** everything that can be checked without a Mac has been checked and passes.
The iOS-specific half is scaffolded and conventional, but it has never been compiled. Expect to fix
something on first build.

---

## What the demo shows

`ExampleSurvey` builds a deterministic branching cave (24 stations, 23 legs, 92 splays) with LRUD
splays and freehand passage walls, then the shared Compose canvas draws it:

- **Plan** — centreline, splays, stations and labels, sketch walls, scale bar
- **Extended elevation** — the cave unrolled onto one plane, which is the most cave-specific piece
  of maths in the whole codebase
- Pan, pinch-zoom, light/dark, and layer toggles

Renders are written to `demo/build/demo/`.

---

## Building

Everything below works on Linux/macOS/Windows except where noted.

```bash
cd kmp

./gradlew :shared:jvmTest          # run the ported test suite (the correctness oracle)
./gradlew :demo:renderDemoPng      # render the shared UI to PNGs, no display needed
./gradlew :demo:run                # open the desktop app (needs a display)
```

### Running on iOS (needs macOS + Xcode)

The Xcode project is **generated rather than committed**, because it was authored on Linux where a
hand-written `.pbxproj` cannot be opened or validated. Generating it is one command:

```bash
brew install xcodegen
cd kmp/iosApp && xcodegen && open iosApp.xcodeproj
```

Then pick a simulator and hit Run. The build runs
`./gradlew :demo:embedAndSignAppleFrameworkForXcode` as a pre-build phase, which compiles the
Kotlin/Native framework and embeds it.

If you would rather install nothing, the manual equivalent takes about two minutes:

1. New Xcode project → iOS → App → SwiftUI, anywhere you like.
2. Replace the generated Swift files with `iosApp/iosApp/iOSApp.swift` and
   `iosApp/iosApp/ContentView.swift`.
3. Add a Run Script build phase, **before** Compile Sources:
   `cd "$SRCROOT/path/to/kmp" && ./gradlew :demo:embedAndSignAppleFrameworkForXcode`
4. Set `ENABLE_USER_SCRIPT_SANDBOXING = NO` (Xcode 14+ blocks Gradle otherwise).
5. Add `$(SRCROOT)/../demo/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` to
   Framework Search Paths, and `-framework SexyTopoDemo` to Other Linker Flags.

The entire iOS-specific surface of this demo is
`demo/src/iosMain/.../MainViewController.kt` (one function) plus those two Swift files.

---

## How it maps to the Android app

| Android app (Java) | Here (Kotlin) | Notes |
| --- | --- | --- |
| `model/graph/Coord2D`, `Coord3D`, `Line`, `Space` | `shared/model/graph/Coords.kt` | Straight translation |
| `model/survey/{Station,Leg,Survey}` | `shared/model/survey/` | `Survey` drops the `DocumentFile` (see below) |
| `model/graph/Projection2D` | `shared/model/graph/Projection2D.kt` | Including the load-bearing y-flip |
| `control/util/Space2DUtils`, `Space3DUtils` | `shared/math/SpaceUtils.kt` | Pure arithmetic in the original too |
| `control/util/Space3DTransformer(ForElevation)` | `shared/math/Space3DTransformer.kt` | The extended-elevation unroll |
| `model/sketch/{Sketch,PathDetail,…}` | `shared/model/sketch/Sketch.kt` | No platform graphics types in either |
| `model/sketch/Colour` (150 values) | `shared/model/sketch/Colour.kt` | **Generated** from the Java enum so values cannot drift |
| `control/io/basic/SurveyJsonTranslater` | `shared/io/SurveyJson.kt` | Same tags, same tolerant two-pass load |
| `control/io/basic/SketchJsonTranslater` | `shared/io/SketchJson.kt` | Cross-sections parsed past, not rebuilt |
| `control/util/SurveyUpdater` (part) | `shared/survey/SurveyBuilder.kt` | Only new-station + splay; see gaps below |
| `control/graph/GraphView` (2,199 lines) | `demo/…/SurveyCanvas.kt` | **Rewritten**, not ported — this is the part no strategy avoids |

Two things worth noticing, because they are the load-bearing findings:

1. **The sketch model needed no translation at all.** Paths are lists of the app's own `Coord2D` in
   survey metres and colours are its own packed RGB ints — there is not a single `android.graphics`
   type in the sketch data. Only the *renderer* is platform-specific.
2. **Storage is where Android leaks into the domain.** The Java `Survey` holds a `DocumentFile` and
   derives `equals`/`hashCode` from a `content://` URI. Worse, the *file format itself* stores
   cross-survey links as those URIs. This port replaces the field with an opaque `location` string;
   a real port needs a platform-neutral link scheme plus a migration — which the Android app would
   benefit from too, since those URIs already break when a survey folder moves.

---

## Deliberate gaps

This is a proof of concept. It does **not** include:

- **Any Bluetooth or instrument support.** The device layer is untouched. On iOS, DistoX/DistoX2
  (Bluetooth Classic SPP) can never work; every current instrument (DistoX-BLE, BRIC4/5, SAP5/6,
  Cavway X1, FCL) is BLE and reachable via CoreBluetooth.
- **Drawing tools.** The canvas renders a sketch; it does not let you draw one. No tool modality,
  eraser, symbols palette, or undo.
- **Symbol artwork.** The 19 UIS SVGs are not carried across; symbols draw as marked points.
- **Cross-sections.** Parsed past in JSON, not reconstructed or drawn.
- **The rest of `SurveyUpdater`** — triple-shot promotion, the three leg-amalgamation algorithms,
  splay upgrade/downgrade, leg editing, subtree-aware deletion.
- **Every exporter** — Therion, Survex, Compass, PocketTopo, SVG, XVI.
- **The Android app adopting this core.** That is the step that would make the work pay for itself
  regardless of the iOS outcome, and it is deliberately not attempted yet. It also needs an Android
  SDK, which the machine this was written on did not have.

---

## If this were taken further

Roughly the order that keeps every intermediate state shippable:

1. **Agree the direction first.** A restructure like this only works upstream; as a fork it dies.
2. **Licensing, in parallel and early.** GPL-3.0 on the App Store needs a Section 7 "App Store
   exception" from every copyright holder — Rich, the eight named contributors, and Beat Heeb for
   the calibration algorithm. It gates release, not development, so it should start first.
3. Extend the shared core to the rest of `SurveyUpdater` and the exporters, gated on byte-identical
   output against the existing `exportTherionFixtures` / `exportSvgFixtures` golden bundles.
4. **Point the Android app at the shared core and ship it.** After this the effort has paid for
   itself even if iOS never happens.
5. Then the iOS-specific work: file handling, CoreBluetooth, the drawing tools.

Two things should be measured on real hardware before committing to any of it: sketching latency
with an Apple Pencil on an iPad, and a CoreBluetooth connection to an actual instrument.

---

## Provenance

Forked from [richsmith/sexytopo](https://github.com/richsmith/sexytopo). GPL-3.0, like the original —
a transliteration is a derivative work, so this code carries the same licence.
