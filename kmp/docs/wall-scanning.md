# Wall scanning with the lidar: how to test it

*Tools → Scan walls (lidar)* opens a camera with a yellow crosshair in the middle. On it you mark
survey stations, draw walls and water lines with an AR pen, and stamp symbols onto the rock. All
the while the lidar records the rock around you. Tap *Done* and the scan is placed in the survey and drawn
onto the plan and the extended elevation.

It needs an iPhone or iPad with lidar: iPhone 12 Pro or later Pro models, or an iPad Pro from 2020
on. On anything else the menu item says why it cannot scan.

> **Status.** The geometry behind this (placing scans, smoothing, walls, LRUDs) is tested on the
> JVM. The iOS screen, `demo/src/iosMain/kotlin/org/hwyl/sexytopo/demo/WallScanner.ios.kt`,
> compiles, and the app builds with Xcode and runs in the iOS Simulator. **It has not yet been run
> on a phone with lidar.** The Simulator has no depth camera, so there the scanner reports itself
> unavailable and *Tools → Wall scans* offers simulated scans instead (see section 5).

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
   symbol appear on the plan, and again on the extended elevation. The scan itself is saved with
   the survey, rock and all.
8. **Review it.** *Tools → Wall scans* opens a panel under the drawing where you can move the scan,
   fix its station marks, re-smooth its lines, or delete it with everything it drew. Anything you
   drew by hand stays. See section 6.

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

## 5. Without a lidar phone: simulated scans

On the iOS Simulator, the desktop build (`./gradlew :demo:run`), the browser build and any phone
without lidar, *Tools → Scan walls (lidar)* says so and opens *Tools → Wall scans* instead. That
panel has two extra buttons:

- **Add a simulated scan** makes up a scan of a 2 m by 1.6 m passage round the survey's own legs,
  walking about 40 m on from the **active station**, with three stations marked, a pen line along
  the foot of the left wall (with a few stray hits for the smoothing to remove) and two symbols.
  Its compass is deliberately 12° out, so you can see anchoring correct it.
- **…with no marks** makes the same scan with nothing marked, so it is placed by Fitted mode and
  draws walls, roof and floor and LRUD splays.

You need a survey with a few legs and its first station set active. Everything in section 6 works
on a simulated scan exactly as on a real one. This is also the quickest way to try review before
going underground.

## 6. Reviewing a scan: Tools → Wall scans

The panel replaces the field bar and toolbar, so the drawing stays visible above it. Every change
is redrawn and saved straight away. Nothing here changes the captured scan: each edit is kept
beside it and can be taken back.

- **Placement.** Says whether the scan is *anchored to its marks*, *fitted to the centerline* or
  placed *by hand*.
  - *Turn* −5°, −1°, +1°, +5° turns the scan about the middle of its track.
  - *N*, *S*, *E*, *W*, *Up*, *Down* move it by the step shown (tap *Step* to switch between 10 cm
    and 1 m).
  - The first nudge switches the scan to *by hand*. *Automatic* puts it back to placing itself.
  - *Lock here* keeps an automatic placement as it is, so later changes to the survey don't move
    it.
  - *Fit from here* fits the scan to the centerline again, starting from where it is now and
    searching only 10° and 2 m around it. Use it when Fitted mode put a scan in the wrong passage:
    drag it roughly into place by hand, then fit.
- **Station marks.** Each mark shows how far it ended up from its station (*3 cm out*). A metre or
  more usually means it was marked under the wrong name. Tap the name to choose the right station,
  or *Remove* the mark.
- **Pen lines.** Each line has a smoothing slider that applies to the whole line, using every raw
  hit the pen kept. *Remove* takes the line out of the drawing.
- **Symbols.** *Remove* any you stamped by mistake.
- **Drawn from the rock.** Switches for *Walls along the legs* (walls on the plan, roof and floor
  on the extended elevation), *LRUD splays* (on stations with no splays of their own), *Splay
  fans* (twelve splays round each such station, from the scan's cross-section there) and
  *Cross-sections at stations* (the outline of the rock, drawn in a cross-section beside each
  station the scan passed that has no cross-section yet). Walls and LRUDs are on by default for a
  fitted scan and off for an anchored one; fans and cross-sections are off until switched on.
  Edit one of those cross-sections by hand and it stops belonging to the scan: later changes in
  review leave it alone.
- **Delete scan** removes the scan, everything it drew and every splay it added.

**Edit the scan's drawing by hand only once you're happy with its review.** Every change in the
panel redraws the scan's own lines and symbols, including any you have moved or partly rubbed
out since, so hand edits to them would be undone. Cross-sections are the exception: one you have
drawn in by hand stops belonging to the scan and is left alone.

**The 3D view** shows the rock of every placed scan as a faint brown cloud round the centreline.

## 7. What is saved, and where

- Each scan is two files beside the survey's own: `Survey.scan-1.json` (the track, marks, every raw
  pen hit, the stamps, the review edits and how it was last placed) and `Survey.scan-1.points`
  (the rock, 12 bytes a point). *Share* puts both in the zip, and importing a survey folder copies
  them.
- Lines and symbols a scan drew are ordinary sketch strokes with a `"scan": "1"` tag, so the app
  can find and replace them. The Android app ignores the tag, so a survey with scans opens there
  with the drawing intact. If Android saves the survey again, the tags are dropped and the drawing
  becomes ordinary hand drawing.
- Splays a scan added have the comment *wall scan 1*, which shows in the table on both apps.
- Renaming a survey, or *Save As*, takes its scans along.
- **If the app stops mid-scan** (a crash, a flat battery), the scanner has written its work down
  every five seconds. The next time you open the scanner or *Tools → Wall scans* in the survey you
  were scanning, that scan is added with the message *Recovered a wall scan…*. In any other survey
  the scanner won't start, and says which survey to open first, so the unfinished scan isn't
  overwritten. If saving the recovered scan fails, it is kept for next time.
- While scanning, the rock behind you is written to files and only the rock near you is kept in
  memory, so a long walk shouldn't run the phone out of memory. The whole scan is read back into
  memory once, when you tap *Done*.

## 8. Known limits of this version

- **Not yet run on a lidar phone.** It compiles and the app runs in the Simulator, but the screen
  itself has only been checked as far as the Simulator allows, which is not at all for the depth
  camera. Expect fixes after the first run on a phone.
- **The touch offset is zero.** *Touch* uses the lens position, not a corner of the phone. *Aim*
  avoids the problem entirely.
- **Review redraws the whole scan on every change.** On a long scan with walls, LRUDs, fans or
  cross-sections switched on, each tap does work in proportion to all its points, so expect a
  pause on a phone. An unmarked scan isn't fitted again on each tap, only when it's first placed
  and when you press *Automatic*.
- **A scan is placed as one rigid piece.** The plan's chaining of long scans into 20–40 m pieces,
  each fitted separately, is not done yet, so ARKit drift over a long walk ends up in the
  placement. Keep fitted scans to a few tens of metres for now.
- **No segments on tracking loss.** If tracking is lost mid-scan, the scan carries on as one piece.
  The track walked while tracking was poor is recorded, left out of fitting, and reported in
  review (*Tracking was poor for N% of the walk*), but the rock seen then is kept. Check the
  *Tracking lost* line while scanning and start a new scan if it lasted.
- **Fitted mode can't place stations that sit right on the floor.** It finds height by keeping the
  legs in open passage, and a leg ten centimetres above the floor can't be told from one through
  it. Within a tall passage it puts the legs as low as the rock allows, because stations usually
  are low; so height in Fitted mode is good to a few decimetres, not centimetres.
- **Review can't yet trim a scan, erase stray points, or stamp a symbol on the captured rock.**
  Only what is listed in section 6.
- **The live slider only reaches the last five metres**, of the line being drawn or the last one
  finished. In review, each line's slider covers the whole line.
- **No lidar capture on Android.** Android and desktop users can open and edit the drawings a scan
  made, and review scans other people took.
