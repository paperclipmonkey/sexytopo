// The field workflow: can a caver actually record a survey on an iPhone with no signal?
//
// The smoke test asks whether the app draws. This asks whether it *works* — create a named survey,
// type readings the way a surveyor reads them off a DistoX display, have three agreeing ones
// promote to a station, correct one that was typed wrong, throw away one that was not worth
// keeping, name the station at the junction, get the lot out as a Survex file, keep it across a
// restart, and still open with the network cut. Each of those is a thing that would make the app
// useless underground if it broke, and none of them is visible in a screenshot.
//
//   node field.mjs <url> [screenshotDir]
import { chromium } from 'playwright'
import { mkdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:8080/index.html'
const shotDir = process.argv[3] ?? 'field-screenshots'
mkdirSync(shotDir, { recursive: true })

// A real file in the app's own format, small enough to read: one leg, two stations.
const EXAMPLE_SURVEY_JSON = JSON.stringify({
  sexyTopoVersionName: 'kmp-port',
  sexyTopoVersionCode: 0,
  name: 'Eastwater',
  activeStation: '2',
  stations: [
    {
      name: '1',
      eeDirection: 'right',
      comment: 'entrance',
      legs: [
        {
          distance: 8.0,
          azimuth: 45.0,
          inclination: -5.0,
          destination: '2',
          wasShotBackwards: false,
          index: 0,
          promotedFrom: [],
        },
      ],
    },
    { name: '2', eeDirection: 'right', comment: '', legs: [] },
  ],
})

const failures = []
const fail = (m) => { failures.push(m); console.error(`FAIL  ${m}`) }
const pass = (m) => console.log(`ok    ${m}`)

const launch = {}
if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH
if (process.env.SMOKE_PROXY) launch.proxy = { server: process.env.SMOKE_PROXY }
const browser = await chromium.launch(launch)
// A phone, because that is where this has to work.
const ctx = await browser.newContext({
  viewport: { width: 420, height: 900 },
  acceptDownloads: true,
})
const page = await ctx.newPage()
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))

// Registered here, before anything is clicked, and deliberately not with waitForEvent at the point
// of use.
//
// Playwright intercepts the native file chooser through the DevTools protocol, and turns that
// interception *on* asynchronously when the first listener is attached. `waitForEvent('filechooser')`
// immediately followed by the click races that: the chooser opens for real, no event is emitted,
// and the check reports that the app never opened a chooser. It failed two runs out of three that
// way while the app was working perfectly - measured at 1 chooser seen in 8 with waitForEvent
// against 6 in 6 with this listener.
let fileChoosersOpened = 0
let chosenFile = null
page.on('filechooser', async (chooser) => {
  fileChoosersOpened++
  if (chosenFile) await chooser.setFiles(chosenFile).catch(() => undefined)
})

const ready = async () => {
  for (let i = 0; i < 60; i++) {
    await page.waitForTimeout(500)
    if ((await page.screenshot()).length > 15000) return true
  }
  return false
}

await page.goto(url, { waitUntil: 'load' })
if (!(await ready())) {
  fail('the app never rendered')
  await browser.close()
  process.exit(1)
}
const box = await (await page.$('canvas')).boundingBox()
const at = (x, y) => page.mouse.click(box.x + x, box.y + y)

// Replaces whatever is in a text field. Backspace and Delete rather than select-all: Compose
// takes a moment to accept keyboard focus after a click, and a Control+A that lands too early is
// silently ignored, leaving the new text appended to the old ("1.5002.75") and the test failing
// somewhere else entirely. Clearing in both directions needs no selection and no caret position.
async function retype(where, text) {
  await at(...where)
  await page.waitForTimeout(300)
  for (let i = 0; i < 16; i++) await page.keyboard.press('Backspace')
  for (let i = 0; i < 16; i++) await page.keyboard.press('Delete')
  await page.keyboard.type(text, { delay: 25 })
  await page.waitForTimeout(200)
}

// Positions are computed from the canvas box, so moving a control a few pixels does not break the
// test while moving it somewhere else rightly does.
const OVERFLOW = [box.width - 16, 26]
const MENU_NEW = [336, 80]
const NAME_FIELD = [210, 442]
const NAME_CONFIRM = [312, 518]
const ADD_READING = [74, 790]
// Same place as "Add reading", because it is the button that becomes it.
const START_SURVEYING = [83, 790]
const FIELD_DISTANCE = [144, 355]
const FIELD_AZIMUTH = [284, 355]
const FIELD_INCLINATION = [144, 429]
const SIGN_TOGGLE = [255, 425]
const MODE_FORWARD = [116, 491]
const MODE_BACKSIGHT = [214, 491]
const CANCEL_READING = [139, 605]
const ADD_SPLAY = [225, 605]
const ADD_LEG = [309, 605]
const TABLE_TAB = [281, 26]
const PLAN_TAB = [325, 26]
const TABLE_ROW = (n) => [210, 66 + 26 * n]
const ACT_EDIT = [276, 448]
const ACT_DELETE_SPLAY = [292, 544]
const EDIT_DISTANCE = [140, 384]
const EDIT_SAVE = [309, 552]
const CONFIRM_DELETE = [292, 496]
const STATION_CHIP = [310, 790]
const STATION_NAME = [210, 260]
const STATION_COMMENT = [210, 336]
const STATION_LRUD_LEFT = [106, 520]
const STATION_LRUD_RIGHT = [175, 520]
const STATION_EE_LEFT = [102, 628]
const STATION_SAVE = [317, 700]
// The overflow menu, by name rather than by pixel.
//
// It lists the saved surveys in the middle, so every row below them moves when the library grows,
// and every row moves when a menu item is added. Both have happened repeatedly, and each time the
// checks that clicked a hard-coded y went on passing while testing the wrong thing or failed
// somewhere unrelated. Computing the row from the menu's own order means one list to update.
const MENU_BEFORE_SURVEYS = ['new', 'rename', 'trip']
const MENU_AFTER_SURVEYS = ['demo', 'export', 'instrument', 'import', 'surveying', 'dark']
const MENU_FIRST_ROW_Y = 80
const MENU_ROW_HEIGHT = 48

const menuRowY = (index) => MENU_FIRST_ROW_Y + MENU_ROW_HEIGHT * index

/** The row for a named item, given how many surveys the library is showing above it. */
function menuRow(name, savedSurveys) {
  const before = MENU_BEFORE_SURVEYS.indexOf(name)
  const after = MENU_AFTER_SURVEYS.indexOf(name)
  if (before < 0 && after < 0) throw new Error(`no menu item called ${name}`)
  const index =
    before >= 0 ? before : MENU_BEFORE_SURVEYS.length + savedSurveys + after
  return [312, menuRowY(index)]
}

/** The delete cross on the nth saved survey's row, which sits at the right-hand edge. */
const savedSurveyDelete = (nth) => [392, menuRowY(MENU_BEFORE_SURVEYS.length + nth)]
const IMPORT_CHOOSE = [284, 494]
const IMPORT_FIRST_ROW = [210, 446]
const SETTING_DISTANCE = [210, 448]
const SETTING_ANGLE = [210, 524]
const SETTINGS_SAVE = [317, 676]
const TRIP_ADD_NAME = [177, 340]
const TRIP_ADD_BUTTON = [317, 336]
const TRIP_ROLE_BOOK = [106, 298]
const TRIP_INSTRUMENT = [210, 498]
const TRIP_SAVE = [317, 810]
const LABEL_TEXT = [210, 442]
const LABEL_PLACE = [316, 518]
// The sketch toolbar is nine equal columns; the bottom row's third cell is the label tool.
const toolColumn = box.width / 9
const TOOL_ROW_Y = box.height - 20
const toolCell = (index) => [toolColumn * (index + 0.5), TOOL_ROW_Y]
// The drawing menu, by name rather than by pixel — for the same reason as the overflow menu.
//
// It opens *upwards* from its toolbar cell, so its bottom row stays put and every row above it
// moves when an item is added. Hard-coded y values for two of these rows had already survived one
// such addition by silently clicking the wrong item.
const DRAWING_MENU = [
  'centre',
  'symbol',
  'cross-section',
  're-aim',
  'move',
  'splays',
  'sketch',
  'labels',
  'grid',
]
const DRAWING_MENU_LAST_ROW_Y = 820
const DRAWING_MENU_ROW_HEIGHT = 48

function drawingMenuRow(name) {
  const index = DRAWING_MENU.indexOf(name)
  if (index < 0) throw new Error(`no drawing-menu item called ${name}`)
  const fromBottom = DRAWING_MENU.length - 1 - index
  return [186, DRAWING_MENU_LAST_ROW_Y - DRAWING_MENU_ROW_HEIGHT * fromBottom]
}

/** A finger drag on the canvas, in canvas coordinates. */
async function drag([x0, y0], [x1, y1]) {
  await page.mouse.move(box.x + x0, box.y + y0)
  await page.mouse.down()
  await page.mouse.move(box.x + x1, box.y + y1, { steps: 12 })
  await page.mouse.up()
}
// "Blocks" in the palette: fourth swatch, second column of the second row.
const PALETTE_BLOCKS = [112, 388]
const CANCEL_DELETE_SURVEY = [237, 516]
const CONFIRM_DELETE_SURVEY = [312, 516]
const EXPORT_SAVE_FILE = [117, 132]

// ---- the app opens on the demo cave, and offers a way out of it ------------------------
// The first screen a new surveyor sees is an example survey that is deliberately never saved.
// Recording controls must not be on it, and something that leads to their own survey must be, or
// the only route off this screen is a three-dot menu.
await page.screenshot({ path: join(shotDir, 'field-first-run.png') })
await at(...START_SURVEYING); await page.waitForTimeout(700)

// That the button leads somewhere usable: the reading dialog is drawn to the canvas, so what is
// checked is that focusing its first field produces the hidden DOM input Compose types through.
await at(...ADD_READING); await page.waitForTimeout(700)
await at(...FIELD_DISTANCE); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-started.png') })
if ((await page.$$('input')).length === 0) {
  fail('the demo cave has no working way through to a survey you can record into')
} else {
  pass('the app opens on the demo cave and offers a way through to your own survey')
}
await page.keyboard.press('Escape'); await page.waitForTimeout(500)

// ---- create a named survey -----------------------------------------------------------
await at(...OVERFLOW); await page.waitForTimeout(500)
await at(...MENU_NEW); await page.waitForTimeout(700)
await at(...NAME_FIELD); await page.waitForTimeout(250)

// Checked here, with a field focused, because that is the only time it exists. Compose paints to a
// canvas and drives text through a hidden DOM input, which is what iOS attaches its on-screen
// keyboard to; no input while focused means nothing can be typed on a phone. Checking after the
// dialog closes finds nothing and says so, which is a broken test rather than a broken app.
const focusedInputs = await page.$$('input')
if (focusedInputs.length === 0) {
  fail('no DOM input while a text field is focused — the on-screen keyboard has nothing to attach to')
} else {
  pass('text entry is wired to a DOM input the on-screen keyboard can use')
}

await page.keyboard.type('Swildons', { delay: 25 })
await page.waitForTimeout(250)
await at(...NAME_CONFIRM); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-new-survey.png') })

// ---- three agreeing readings promote to a station -------------------------------------
// The minus sign is typed with the +/- button rather than the keyboard, because that is the only
// way it can be entered on a phone: no iOS or Android numeric keypad has a minus key.
async function reading(d, a, i, { splay = false, mode = null } = {}) {
  await at(...ADD_READING); await page.waitForTimeout(700)
  if (mode) { await at(...mode); await page.waitForTimeout(250) }
  await at(...FIELD_DISTANCE); await page.waitForTimeout(200)
  await page.keyboard.type(String(d), { delay: 20 })
  await at(...FIELD_AZIMUTH); await page.waitForTimeout(200)
  await page.keyboard.type(String(a), { delay: 20 })
  await at(...FIELD_INCLINATION); await page.waitForTimeout(200)
  await page.keyboard.type(String(Math.abs(i)), { delay: 20 })
  if (i < 0) { await at(...SIGN_TOGGLE); await page.waitForTimeout(200) }
  await page.waitForTimeout(250)
  await at(...(splay ? ADD_SPLAY : ADD_LEG)); await page.waitForTimeout(700)
}
await reading(5.42, 12.5, -3.0)
await reading(5.43, 12.7, -2.9)
await reading(5.41, 12.4, -3.1)
await page.screenshot({ path: join(shotDir, 'field-readings.png') })

// ---- a cross-section at a station -----------------------------------------------------------
// What a caver draws when the plan alone cannot say what shape the passage is. Placed here, right
// after the readings, because this is the one moment the test knows where a station is on screen:
// the view has just refitted around two of them, and field-readings.png shows station 1 at the
// bottom left.
//
// The whole model, the projection and the bearing heuristic were ported and tested long ago; what
// this checks is that the tool reaches them and that the result is saved with the sketch.
await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...drawingMenuRow('cross-section')); await page.waitForTimeout(500)
await at(140, 712); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-cross-section.png') })

const sections = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? []).length
})
if (sections === null) {
  fail('the plan sketch was not saved, so the cross-section could not be checked')
} else if (sections < 1) {
  fail('tapping a station with the cross-section tool did not add one')
} else {
  pass('a cross-section can be dropped at a station, and is saved with the sketch')
}

// ---- and can be corrected afterwards ---------------------------------------------------
// Both decisions the app makes when a section appears are guesses: the bearing comes from a
// heuristic and the position comes from wherever a finger landed. A section cutting the passage at
// the wrong angle is not a rough drawing, it is a wrong one — so being able to say so matters, and
// the gesture wiring is exactly the sort of thing that can silently do nothing. (It has: the
// symbol tool stamped nothing at all for a while, because a drag detector never fires for a tap.)
const firstSection = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? [])[0] ?? null
})

const placedSection = await firstSection()

await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...drawingMenuRow('move')); await page.waitForTimeout(500)
await drag([140, 712], [210, 660]); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-cross-section-moved.png') })

const movedSection = await firstSection()
if (!placedSection || !movedSection) {
  fail('the plan sketch was not saved, so moving a cross-section could not be checked')
} else if (
  movedSection.location.x === placedSection.location.x &&
  movedSection.location.y === placedSection.location.y
) {
  fail('dragging a cross-section with the move tool did not move it')
} else if (movedSection.angle !== placedSection.angle) {
  fail('moving a cross-section also changed its bearing')
} else {
  pass('a cross-section can be dragged somewhere clearer on the plan')
}

await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...drawingMenuRow('re-aim')); await page.waitForTimeout(500)
// Grab the section where it now is and swing it round its station.
await drag([210, 660], [330, 760]); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-cross-section-aimed.png') })

const aimedSection = await firstSection()
if (!aimedSection) {
  fail('the plan sketch was not saved, so re-aiming a cross-section could not be checked')
} else if (aimedSection.angle === movedSection.angle) {
  fail('dragging a cross-section with the re-aim tool did not change its bearing')
} else if (
  aimedSection.location.x !== movedSection.location.x ||
  aimedSection.location.y !== movedSection.location.y
) {
  fail('re-aiming a cross-section also moved it')
} else {
  pass('a cross-section can be re-aimed when the bearing the app guessed is wrong')
}

// Back to drawing, so nothing after this drops a section by accident.
await at(...toolCell(1)); await page.waitForTimeout(400)

// Reads the saved survey back. The checks below assert against the file rather than the screen,
// because the file is what the surveyor takes home and hands to Therion.
const savedLegs = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  if (!key) return []
  return (JSON.parse(localStorage.getItem(key)).stations ?? []).flatMap((st) => st.legs ?? [])
})

// A splay is written with the null station's name rather than no destination at all, so "has a
// destination" has to mean "has a real one".
const SPLAY_DESTINATION = '-'
const isSplay = (leg) => leg.destination === SPLAY_DESTINATION
const isConnecting = (leg) => !isSplay(leg)

// A downward shot only reaches the survey if the +/- button worked, and a downward shot is half of
// every survey ever made. Nothing else in this file could catch its loss: the readings would land
// as +3 instead of -3 and the cave would come out mirrored about the horizontal.
const promoted = (await savedLegs()).find(isConnecting)
if (!promoted) {
  fail('three agreeing readings did not promote to a station')
} else if (!(promoted.inclination < 0)) {
  fail(`the shot went in with the wrong sign (inclination ${promoted.inclination}) — the +/- button did not work`)
} else {
  pass('a downward shot can be entered, with the sign typed on a keypad that has no minus key')
}

// ---- it is written to storage ----------------------------------------------------------
const keys = await page.evaluate(() =>
  Object.keys(localStorage).filter((k) => k.startsWith('sexytopo:')),
)
const dataFile = keys.find((k) => k.endsWith('Swildons.data.json'))
if (!dataFile) {
  fail(`the survey was not saved (keys: ${keys.join(', ') || 'none'})`)
} else {
  pass('the survey is written to browser storage in SexyTopo\'s own file layout')
}

// The legs must actually be in the file, not just an empty survey with the right name.
const legCount = await page.evaluate((key) => {
  const json = JSON.parse(localStorage.getItem(key))
  const stations = json.stations ?? []
  return stations.reduce((n, s) => n + ((s.legs ?? []).length), 0)
}, dataFile)
if (legCount < 1) {
  fail(`the saved survey has no legs (${legCount})`)
} else {
  pass(`the saved survey holds its legs (${legCount})`)
}

// ---- a mistyped reading can be corrected --------------------------------------------------
// The reason this matters more than it sounds: without it the app is a one-way funnel. A surveyor
// who fat-fingers a bearing underground, by head torch, with cold hands, has no way back, and the
// survey they carry out is wrong in a way nothing downstream can detect.
await reading(1.5, 200, 0, { splay: true }) // a splay: something disposable to correct

await at(...TABLE_TAB); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-table.png') })

const splayRow = (await savedLegs()).findIndex(isSplay)
if (splayRow < 0) {
  fail('the splay was not added, so editing could not be tested')
} else {
  // Row 1 is the leg to station 2; row 2 is the splay.
  await at(...TABLE_ROW(2)); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-leg-actions.png') })
  await at(...ACT_EDIT); await page.waitForTimeout(700)
  await retype(EDIT_DISTANCE, '2.75')
  await page.screenshot({ path: join(shotDir, 'field-edit-reading.png') })
  await at(...EDIT_SAVE); await page.waitForTimeout(900)

  const afterEdit = await savedLegs()
  const editedSplay = afterEdit.find(isSplay)
  if (!editedSplay || Math.abs(editedSplay.distance - 2.75) > 0.001) {
    fail(`the correction did not reach the saved survey (${editedSplay ? editedSplay.distance : 'no splay'})`)
  } else {
    pass('a mistyped reading can be corrected, and the correction is saved')
  }
  // The dangerous failure is the silent one: SurveyUpdater.editLeg swaps the whole leg, so an edit
  // that forgot the destination would take every station beyond it out of the survey.
  if (!afterEdit.some(isConnecting)) {
    fail('editing destroyed the connected leg — the survey lost a station')
  } else {
    pass('editing leaves the rest of the survey standing')
  }

  // ---- and a bad reading can be thrown away ------------------------------------------------
  await at(...TABLE_ROW(2)); await page.waitForTimeout(700)
  await at(...ACT_DELETE_SPLAY); await page.waitForTimeout(600)
  await page.screenshot({ path: join(shotDir, 'field-confirm-delete.png') })
  await at(...CONFIRM_DELETE); await page.waitForTimeout(900)

  const afterDelete = await savedLegs()
  if (afterDelete.some(isSplay)) {
    fail('the splay was still there after deleting it')
  } else if (!afterDelete.some(isConnecting)) {
    fail('deleting the splay took the connected leg with it')
  } else {
    pass('a bad reading can be deleted, and only it')
  }
}

await at(...PLAN_TAB); await page.waitForTimeout(600)

// ---- a station can be named, and told what is there ----------------------------------------
// Numbered stations are fine down a straight passage and useless at a junction: the surveyor's
// notebook says "sump", and a survey where that name exists only on paper cannot be tied to the
// next trip's. The comment is the same argument — a lead nobody wrote down is a lead nobody goes
// back for.
await at(...STATION_CHIP); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-station.png') })
await retype(STATION_NAME, 'Sump')
await at(...STATION_COMMENT); await page.waitForTimeout(250)
await page.keyboard.type('Continues, too tight for me', { delay: 15 })
// Passage size from a tape rather than an instrument: two numbers become two splays, square to
// the passage, and a cross-section can then be drawn from a hand-booked survey.
await retype(STATION_LRUD_LEFT, '1.5')
await retype(STATION_LRUD_RIGHT, '2')
await at(...STATION_EE_LEFT); await page.waitForTimeout(250)
await page.screenshot({ path: join(shotDir, 'field-station-named.png') })
await at(...STATION_SAVE); await page.waitForTimeout(900)

const named = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? (JSON.parse(localStorage.getItem(key)).stations ?? []) : []
})
const sump = named.find((st) => st.name === 'Sump')
if (!sump) {
  fail(`the station was not renamed (stations: ${named.map((st) => st.name).join(', ') || 'none'})`)
} else if (sump.comment !== 'Continues, too tight for me') {
  fail(`the station's comment was not kept (${JSON.stringify(sump.comment)})`)
} else if (sump.eeDirection !== 'left') {
  fail(`the extended-elevation direction was not kept (${sump.eeDirection})`)
} else {
  pass('a station can be named, commented and pointed the right way in the extended elevation')
}

const walls = (sump?.legs ?? []).filter(isSplay)
if (walls.length !== 2) {
  fail(`the tape measurements did not become splays (${walls.length} of 2)`)
} else if (!walls.some((w) => Math.abs(w.distance - 1.5) < 0.001)) {
  fail(`the left-hand wall measurement is not in the survey: ${JSON.stringify(walls)}`)
} else {
  pass('passage size can be booked with a tape, and becomes splays like any other')
}

// ---- and the words that are not numbers ----------------------------------------------------
// "Boulder choke", "sump", "continues" — what a surveyor writes on the drawing rather than in the
// table. The sketch model has carried text details since the port began and the canvas has always
// drawn them; until now nothing could create one, and the toolbar button was disabled.
await at(...toolCell(2)); await page.waitForTimeout(500)
await at(200, 400); await page.waitForTimeout(700)
await at(...LABEL_TEXT); await page.waitForTimeout(250)
await page.keyboard.type('boulder choke', { delay: 15 })
await page.screenshot({ path: join(shotDir, 'field-label.png') })
await at(...LABEL_PLACE); await page.waitForTimeout(900)

const labels = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  // "labels" is the key SketchJson writes, and the one the Android app reads.
  return (JSON.parse(localStorage.getItem(key)).labels ?? []).map((label) => label.text)
})
if (labels === null) {
  fail('the plan sketch was not saved at all')
} else if (!labels.includes('boulder choke')) {
  fail(`the label did not reach the saved sketch (labels: ${JSON.stringify(labels)})`)
} else {
  pass('a label can be written onto the sketch, and is saved with it')
}

// Back to drawing, so nothing after this places a label by accident.
await at(...toolCell(1)); await page.waitForTimeout(400)

// ---- and the symbols that are not words either ----------------------------------------------
// The nineteen UIS symbols, taken from the app's own vector drawables and drawn through a path
// parser in commonMain. A stamped symbol has to carry the Therion name the canvas looks its
// artwork up by; if those two ever disagreed every symbol would silently draw as a fallback dot.
await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...drawingMenuRow('symbol')); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-symbol-palette.png') })
await at(...PALETTE_BLOCKS); await page.waitForTimeout(700)
await at(200, 300); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-symbol.png') })

const symbols = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key)).symbols ?? []).map((s) => s['symbol-id'])
})
if (symbols === null) {
  fail('the plan sketch was not saved, so the symbol could not be checked')
} else if (!symbols.includes('blocks')) {
  fail(`the stamped symbol did not reach the saved sketch (${JSON.stringify(symbols)})`)
} else {
  pass('a UIS symbol can be stamped on the sketch, under the name Therion uses')
}

// Back to drawing, so nothing after this stamps by accident.
await at(...toolCell(1)); await page.waitForTimeout(400)

// ---- tolerances that suit the instrument in the surveyor's hand -----------------------------
// The defaults assume a DistoX: 1.7 degrees of spread. A hand-held compass does not come close, so
// on a trip with compass and tape three readings of the same leg never agree, nothing is ever
// promoted, and the survey silently fills with splays while the surveyor wonders what is wrong.
// This walks that failure and then fixes it, which is the only way to show the setting does
// anything.
const sloppy = [
  [3.0, 100, 0],
  [3.05, 104, 2],
  [2.95, 96, -2],
]
const connectingLegs = async () => (await savedLegs()).filter(isConnecting).length

const beforeSloppy = await connectingLegs()
for (const [d, a, i] of sloppy) await reading(d, a, i)
if ((await connectingLegs()) !== beforeSloppy) {
  fail('readings 8 degrees apart were promoted under the DistoX defaults')
} else {
  pass('readings too far apart to be the same shot are refused')
}

await at(...OVERFLOW); await page.waitForTimeout(500)
await at(...menuRow('surveying', 1)); await page.waitForTimeout(800)
await retype(SETTING_DISTANCE, '0.5')
await retype(SETTING_ANGLE, '12')
await page.screenshot({ path: join(shotDir, 'field-surveying-settings.png') })
await at(...SETTINGS_SAVE); await page.waitForTimeout(700)

for (const [d, a, i] of sloppy) await reading(d, a, i)
if ((await connectingLegs()) !== beforeSloppy + 1) {
  fail('loosening the tolerances did not let the same readings make a station')
} else {
  pass('loosened tolerances let a compass-and-tape survey make stations')
}

// Saved, because a surveyor sets these once at the entrance and the phone may not last the trip.
const savedSettings = await page.evaluate(() =>
  localStorage.getItem('sexytopo:f:settings.txt'),
)
if (!savedSettings || !savedSettings.includes('maxAngleDelta=12')) {
  fail(`the tolerances were not written to storage (${JSON.stringify(savedSettings)})`)
} else {
  pass('the tolerances survive the app being closed')
}

// ---- who was on the trip ---------------------------------------------------------------
// Every exporter in the port already knew how to write a team and a date; until there was a
// dialog, every file this app produced went out anonymous. A survey that does not say who made it
// cannot be checked against anybody's notebook.
await at(...OVERFLOW); await page.waitForTimeout(500)
await at(...menuRow('trip', 1)); await page.waitForTimeout(800)
await at(...TRIP_ADD_NAME); await page.waitForTimeout(250)
await page.keyboard.type('L. Waterworth', { delay: 15 })
await at(...TRIP_ADD_BUTTON); await page.waitForTimeout(600)
await at(...TRIP_ROLE_BOOK); await page.waitForTimeout(300)
await at(...TRIP_INSTRUMENT); await page.waitForTimeout(250)
await page.keyboard.type('DistoX2', { delay: 15 })
await page.screenshot({ path: join(shotDir, 'field-trip.png') })
await at(...TRIP_SAVE); await page.waitForTimeout(800)

const trip = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? JSON.parse(localStorage.getItem(key)).trip ?? null : null
})
if (!trip) {
  fail('the trip details were not saved with the survey')
} else if (!JSON.stringify(trip).includes('L. Waterworth')) {
  fail(`the team did not reach the saved survey (${JSON.stringify(trip).slice(0, 120)})`)
} else {
  pass('a trip records who was there, with what, and on what date')
}

// ---- and the survey can leave the phone as a file ------------------------------------------
// The clipboard reaches an email. Only a file reaches Therion, and a survey that cannot get into
// Therion is a weekend of somebody's life spent producing something they then have to type up
// again from a photograph of a screen.
await at(...OVERFLOW); await page.waitForTimeout(500)
await page.screenshot({ path: join(shotDir, 'field-menu.png') })
await at(...menuRow('export', 1)); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-export.png') })

const download = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...EXPORT_SAVE_FILE),
]).then(([d]) => d)

if (!download) {
  fail('Save file produced no download — the survey cannot leave the phone as a file')
} else if (download.suggestedFilename() !== 'Swildons.svx') {
  fail(`the file came out named ${download.suggestedFilename()}, which Survex will not open`)
} else {
  pass('the survey saves as a correctly named Survex file')

  // What is actually in it. A download that arrives empty, or dated by a fixture, or missing the
  // station the surveyor named, is a file that fails on the laptop rather than on the phone —
  // which is the worst place to find out.
  const svx = readFileSync(await download.path(), 'utf8')
  const today = new Date()
  const stamp = [
    today.getFullYear(),
    String(today.getMonth() + 1).padStart(2, '0'),
    String(today.getDate()).padStart(2, '0'),
  ].join('-')
  if (!svx.includes('*begin Swildons')) {
    fail('the exported file is not this survey')
  } else if (!svx.includes(stamp)) {
    fail(`the export is not dated today (looking for ${stamp}) — the device clock is not reaching it`)
  } else if (!svx.includes('Sump')) {
    fail('the station the surveyor named is not in the export')
  } else if (!svx.includes('L. Waterworth') || !svx.includes('DistoX2')) {
    fail('the trip team and instrument did not reach the Survex file')
  } else {
    pass('the exported file carries the survey, the date, the named station and the team')
  }
}
await page.screenshot({ path: join(shotDir, 'field-export-saved.png') })
await at(...PLAN_TAB); await page.waitForTimeout(600)

// ---- a leg shot from the far end goes in the right way round -------------------------------
// Backsight mode is how a passage gets surveyed on the way back out, and it is the one setting
// that can be wrong without the numbers showing it: the readings look perfectly ordinary and the
// cave comes out pointing the other way. The stored leg has to carry the flag, and the table has
// to show the reading the way the surveyor took it.
await reading(4.0, 300, 5, { mode: MODE_BACKSIGHT })
await reading(4.01, 300.2, 5.1)
await reading(3.99, 299.8, 4.9)
// The field bar has to say so while it is on: this is the one setting whose effect is invisible.
await page.screenshot({ path: join(shotDir, 'field-backsight-mode-on.png') })

const backsights = (await savedLegs()).filter((l) => isConnecting(l) && l.wasShotBackwards)
if (backsights.length !== 1) {
  fail(`backsight mode did not store a reversed leg (${backsights.length} found)`)
} else if (Math.abs(backsights[0].azimuth - 120) > 1) {
  // Stored pointing the way the passage runs, not the way the instrument was pointed.
  fail(`the backsight was stored at ${backsights[0].azimuth} degrees rather than turned round`)
} else {
  pass('a leg shot from the far end is stored the right way round, and flagged as a backsight')
}

// Back to forward, so nothing after this inherits it.
await at(...ADD_READING); await page.waitForTimeout(600)
await at(...MODE_FORWARD); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-input-mode.png') })
await at(...CANCEL_READING); await page.waitForTimeout(500)

// ---- and the demo cave stays a demo --------------------------------------------------------
// The app opens on an example survey, which is where a new surveyor is most likely to press
// something. Anything recorded there is thrown away at the next restart, so nothing may record
// there: the buttons over the demo cave are a way back to your own survey and nothing else.
const demoKeys = await page.evaluate(() =>
  Object.keys(localStorage).filter((k) => k.toLowerCase().includes('demo')),
)
if (demoKeys.length > 0) {
  fail(`the demo cave was written to storage (${demoKeys.join(', ')}) — readings are going into a fixture`)
} else {
  pass('nothing is ever recorded into the demo cave')
}

// ---- it survives a restart --------------------------------------------------------------
await page.reload({ waitUntil: 'load' })
await ready()
await page.waitForTimeout(1500)
await page.screenshot({ path: join(shotDir, 'field-after-restart.png') })

const restored = await page.evaluate(() =>
  Object.keys(localStorage).some((k) => k.endsWith('Swildons.data.json')),
)
if (!restored) {
  fail('the survey did not survive a restart')
} else {
  pass('the survey survives closing and reopening the app')
}

// ---- and the browser has been asked not to throw it away ---------------------------------
// localStorage is best-effort storage by specification: a browser may reclaim it under storage
// pressure. navigator.storage.persist() is the only way to ask it not to, and a survey the browser
// is entitled to delete is not really saved. Headless Chromium refuses the request (no engagement
// history), which is why this asserts the *asking* rather than the granting — and why the app
// carries a warning for exactly the answer this run gets.
const durability = await page.evaluate(() =>
  globalThis.__sexytopoStorage ? globalThis.__sexytopoStorage.state : 'never asked',
)
if (durability === 'never asked' || durability === 'asking') {
  fail(`the app never asked the browser to keep its storage (state: ${durability})`)
} else {
  pass(`the app asks the browser to keep saved surveys (this browser answered: ${durability})`)
}

// ---- and it opens with no signal ---------------------------------------------------------
await page.waitForTimeout(3000) // let the worker finish caching the module graph
const worker = await page.evaluate(async () => {
  const r = await navigator.serviceWorker.getRegistration()
  return r && r.active ? 'active' : 'none'
})
if (worker !== 'active') {
  fail('no active service worker — the app will not open underground')
} else {
  pass('a service worker is installed')
}

await ctx.setOffline(true)
await page.reload({ waitUntil: 'load' }).catch(() => undefined)
const offlineUp = await ready()
await page.screenshot({ path: join(shotDir, 'field-offline.png') })
if (!offlineUp) {
  fail('the app did not load with the network off')
} else {
  pass('the app loads and draws with no network at all')
}
await ctx.setOffline(false)

// ---- and a survey can be thrown away on purpose ---------------------------------------------
// Last, because it removes the thing every check above was asserting about. Somebody makes a test
// survey on the way to the cave and wants it gone; without this the library only ever grows, and
// on a phone the delete control sits a few millimetres from the one that opens it — so it asks
// first, and this checks that it asks.
await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...savedSurveyDelete(0)); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-confirm-delete-survey.png') })

// A Cancel that missed its button would leave the dialog up and also leave the survey intact, so
// this check on its own could pass for the wrong reason. What gives it teeth is the real delete
// below: that only works from a dismissed dialog, so if Cancel did nothing, the next check fails.
const beforeCancel = await savedLegs()
await at(...CANCEL_DELETE_SURVEY); await page.waitForTimeout(700)
if ((await savedLegs()).length !== beforeCancel.length) {
  fail('cancelling the delete removed the survey anyway')
} else {
  pass('a delete can be called off')
}

await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...savedSurveyDelete(0)); await page.waitForTimeout(700)
await at(...CONFIRM_DELETE_SURVEY); await page.waitForTimeout(900)

const left = await page.evaluate(() =>
  Object.keys(localStorage).filter((k) => k.includes('Swildons')),
)
if (left.length > 0) {
  fail(`deleting left ${left.length} of the survey's files behind: ${left.slice(0, 3).join(', ')}`)
} else {
  pass('deleting a survey removes it and everything in it')
}

// ---- a survey can come back in ---------------------------------------------------------------
// Last, because it adds a survey to the library and would shift the menu rows the delete check
// above clicks on. The other half of exporting, and the half that decides whether a survey can be
// recovered after a phone dies or continued from somebody else's copy. The browser has no folder to drop a file
// into, so its chooser writes the file into the app's own storage and one shared code path imports
// it exactly as iOS does with a file dropped into the Files app.
await at(...OVERFLOW); await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'field-import-menu.png') })
await at(...menuRow('import', 0)); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-import-dialog.png') })

chosenFile = {
  name: 'Eastwater.data.json',
  mimeType: 'application/json',
  buffer: Buffer.from(EXAMPLE_SURVEY_JSON, 'utf8'),
}
const choosersBefore = fileChoosersOpened
await at(...IMPORT_CHOOSE)
await page.waitForTimeout(2000)
if (fileChoosersOpened === choosersBefore) {
  fail('the file chooser never opened, so nothing can be imported in the browser')
} else {
  // The chooser is native and asynchronous; the dialog re-reads its list while it is open.
  await page.waitForTimeout(1600)
  await page.screenshot({ path: join(shotDir, 'field-import.png') })
  await at(...IMPORT_FIRST_ROW); await page.waitForTimeout(1200)

  const imported = await page.evaluate(() =>
    Object.keys(localStorage).filter((k) => k.includes('Eastwater/')),
  )
  if (imported.length === 0) {
    fail('the chosen file did not become a survey in the library')
  } else {
    pass('a survey file can be brought in from outside the app')
  }
}

if (pageErrors.length > 0) {
  fail(`the page threw while being used:\n      ${pageErrors.slice(0, 3).join('\n      ')}`)
}

await browser.close()
if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`)
  process.exit(1)
}
console.log('\nField workflow test passed.')
