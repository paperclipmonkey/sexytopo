# Wall scanning with the lidar: how to test it

*Tools → Scan walls (lidar)* opens a camera with a yellow crosshair in the middle. On it you mark
survey stations, draw walls and water lines with an AR pen, and stamp symbols onto the rock. All
the while the lidar records the rock around you. Tap *Done* and the scan is placed in the survey and drawn
onto the plan and the extended elevation.

It needs an iPhone or iPad with lidar: iPhone 12 Pro or later Pro models, or an iPad Pro from 2020
on. On anything else the menu item says why it cannot scan.

> **Status.** The geometry behind this (placing scans, smoothing, walls, LRUDs) is tested on the
> JVM. The iOS screen, `demo/src/iosMain/kotlin/org/hwyl/sexytopo/demo/WallScanner.ios.kt`, was
> written on a machine without a Mac and **has never been compiled for iOS**. The first Xcode build
> is its first check. If it fails to compile, the error will almost certainly be in that one file.

## 1. Build it onto the phone

Follow *Onto your own phone, step by step* in `kmp/README.md`, checking out this branch first:

```bash
git clone https://github.com/paperclipmonkey/sexytopo.git
cd sexytopo
git checkout claude/upbeat-cerf-51ab3i
cd kmp/iosApp && xcodegen && open iosApp.xcodeproj
```

Then set your signing team and bundle identifier, plug the phone in, and press ⌘R. The first build
takes five to fifteen minutes.

The first time you open the scanner, iOS asks for the camera and for location. Allow both. ARKit
needs your location to work out which way north is.

## 2. Try it at home first (ten minutes)

You can test everything except the cave in a room or a corridor.

1. **Make a tiny survey.** In the app, create a new survey and use *Tools → Add a leg* to type in
   two legs, for example 1→2 4 m at 0°, and 2→3 4 m at 90°. Put a sticky note on the floor or wall
   for each of stations 1, 2 and 3, roughly where the legs say they are.
2. **Open the scanner.** Stand by station 1 and choose *Tools → Scan walls (lidar)*.
3. **Mark two stations.** The middle of the top row of buttons shows the selected station, and
   `<` and `>` change it.
   - **Aim** (recommended): from about a metre away, put the crosshair on the sticky note and tap
     *Aim*. The status panel says *Marked 1 by aiming*, and the picker moves on to the next
     station.
   - **Touch**: hold the phone's camera lens right against the mark and tap *Touch*. This is less
     accurate than aiming, because the lens sits a centimetre or two off the surface.
   - Walk to station 2 or 3 and mark it the same way. Two marked stations at least a metre apart
     fix the scan. A third gives a check.
4. **Draw with the pen.** Press and *hold* **Hold to draw**, then slowly sweep the crosshair along
   the bottom edge of a wall. Let go to finish the line. The small map at the top right shows your
   lines and the phone's track. *Wall*/*Water* switches the colour of the next line (water lines
   come out blue).
5. **Try the slider.** While drawing, or straight after letting go, move *Smooth*:
   - At 0% you get exactly what the lidar hit, wobbles and all.
   - Higher values drop stray hits and smooth the line.
   - It only changes the last five metres of a line (or the last line finished). Anything further
     back is frozen. In review you can re-smooth a whole line; that comes later.
6. **Stamp a symbol.** Tap the symbol button (it starts on *stalactite*) to cycle through the 19
   symbols. Aim at something and tap *Stamp*. Water flow and draught point the way the phone is
   facing.
7. **Tap Done.** You're back on the drawing, and a strip under the app bar says what happened, for
   example: *Anchored to 2 stations, worst 3 cm out. Drew 2 pen lines, 1 symbol.* Your lines and
   symbol appear on the plan, and again on the extended elevation.
8. **Undo it.** *Tools → Remove last wall scan* takes that scan's drawing out again. Anything
   you drew by hand stays.

Things worth checking at home:

- **Do the lines land in the right place?** A line along a wall should sit about where that wall
  is relative to your stations.
- **Is anything mirrored or rotated?** Mark the stations in a different order and check the drawing
  still agrees.
- **What does the "worst … out" number say?** A few centimetres is good. A metre or more means a
  station was marked under the wrong name, or the legs you typed don't match the room.

## 3. Fitted mode: an old survey with no marks

This is for the resurvey projects, where there's a centerline but no surviving station marks.

1. Open a survey that has a centerline, for example imported from Survex or Therion.
2. Make the station you're starting beside the **active station**: open its station menu on the
   plan and choose *Set Active*. It's drawn highlighted. That's the pin the scan is placed from,
   and the station the scanner's picker starts on.
3. Open the scanner and **don't mark any stations**. Just walk and scan: point the phone at the
   walls, roof and floor as you go, keeping the passage within five metres. Pen lines and symbols
   work here too.
4. Tap *Done*. The scan is fitted to the centerline by shape. The message says *Fitted to the
   centerline*, and adds:
   - *Could be N m either way along the passage* when the passage was too straight and featureless
     to pin down. The LRUDs are still good; the position along the passage is a guess.
   - *N% of the centerline runs through scanned rock: check the old survey here* when the old
     survey disagrees with the rock.
5. A fitted scan also draws **walls along every leg** it covered, and adds **LRUD splays** to
   stations that had no splays of their own. Stations that already had splays are left alone, so a
   scan never overwrites what somebody measured.

Fitting takes a second or two on the phone after *Done*. The screen is busy during that time.

To try it at home: survey a corridor with typed legs, stand at station 1, make it the active
station with *Set Active*, and scan the corridor without marking anything.

## 4. In the cave: what to look at

The scanner's status panel shows, from the top:

- **Tracking limited or lost**, only when there's a problem. Give it more light, move more slowly,
  and point at rock with some detail.
- **N boxes of rock**: how much of the passage it has measured, in 5 cm boxes. If this stops
  rising while you sweep, the lidar isn't seeing anything.
- **Marked: 1, 2 · N lines · N symbols**: what you've captured.
- **Crosshair on rock 1.2 m away**: whether *Aim*, *Stamp* and the pen will work right now.
  *No rock in range* means the wall is more than about five metres away.

The questions that matter most, in order:

1. **Does tracking survive** in a typical passage, with your lights? Note where it fails: water,
   dark rock, squeezes, turning on the spot.
2. **Do anchored scans land on the survey?** The "worst … out" figure is the check.
3. **Is the pen usable one-handed with a light?** And is about 50% smoothing right?
4. **On an old survey, does Fitted mode find the right place?** Compare it with where you know you
   were.
5. **How long can one scan run** before memory or battery becomes a problem? Long walks are meant
   to be fine: only the rock near you is kept in memory.

## 5. Known limits of this first version

- **The iOS screen has never been compiled or run.** Expect fixes after the first Xcode build.
- **The touch offset is zero.** *Touch* uses the lens position, not a corner of the phone. *Aim*
  avoids the problem entirely.
- **Raw scan data is kept only until *Done*.** The scan's points aren't yet saved with the survey,
  so a scan can't be re-placed or re-smoothed later. What it drew is saved, as ordinary sketch
  lines and symbols.
- ***Remove last wall scan* only remembers the latest scan in this session.** It doesn't remove LRUD
  splays a fitted scan added. Delete those from the table if you want them gone.
- **The slider only reaches the last five metres**, of the line being drawn or the last one
  finished.
- **No lidar capture on Android.** Android and desktop users can open and edit the drawings a scan
  made.
