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

/**
 * A Survex file as another tool would write it: no SexyTopo version stamp, a `*begin`, a passage
 * block whose station comment has to survive the LRUD placeholder columns, and a trailing comment
 * on the data line that a third-party file means for that leg.
 */
const EXAMPLE_SURVEX = [
  '*begin BarPot',
  '*title "Bar Pot"',
  '*date 2024.03.17',
  '*team "A Caver" instruments',
  '*calibrate declination 0.0',
  '*data normal from to tape compass clino',
  '1\t2\t8.00\t45.00\t-5.00\t; rift',
  '2\t..\t3.10\t120.00\t10.00',
  '*data passage station left right up down',
  '1\t-\t-\t-\t-\tentrance',
  '*end BarPot',
  '',
].join('\n')

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
// `let` rather than `const`: the last block resizes to an iPhone SE and every pixel helper below
// screenshots `{clip: box}`, so the canvas has to be re-measured there or the clip falls outside
// the viewport and Playwright refuses it. Reassigned once, at the resize.
let box = await (await page.$('canvas')).boundingBox()

// A guard against a mistake this file has made four times, each costing a six-minute run to find.
//
// Every pixel helper hands `page.evaluate` a screenshot and some values, in an array written after
// the callback. The callback destructures them under its *own* names, so nothing checks the two
// against each other, and a name that is not in scope out here is only found when the helper is
// first called — which
// on this file's slowest path is several minutes in, and reports itself as whatever check happened
// to be running. Reading this file's own source and checking each of those names is declared
// somewhere turns that into an error before the browser is even launched.
{
  const source = readFileSync(new URL(import.meta.url), 'utf8')
  const passed = [...source.matchAll(/\}, \[b64,\s*([A-Za-z_$][\w$]*)\]\)/g)].map((m) => m[1])
  const declared = new RegExp(
    `(?:const|let|var)\\s+(?:${passed.join('|')})\\b|\\((?:${passed.join('|')})\\)\\s*=>`,
  )
  for (const name of new Set(passed)) {
    const isDeclared = new RegExp(`(?:const|let|var)\\s+${name}\\b|\\(\\s*${name}\\s*[,)]`)
    if (!isDeclared.test(source)) {
      throw new Error(
        `field.mjs passes "${name}" into page.evaluate but nothing out here declares it — ` +
          'the callback\'s own parameter names are not what has to match',
      )
    }
  }
  void declared
}

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
// `SexyTopoColours.panelBackground`, the green the sketch toolbar is drawn on and nothing else at
// the bottom of the screen is.
const SKETCH_PANEL = [127, 175, 127]
const TABLE_TAB = [281, 26]
const PLAN_TAB = [325, 26]
const TABLE_ROW = (n) => [210, 66 + 26 * n]
// The leg menu's buttons are found rather than counted from the top of the screen. The dialog is
// centred, its height depends on how many actions the row can take, and the actions a row can take
// depend on the survey — a leg with splays hanging off its far end cannot be made back into one.
// A fixed y for "Edit reading" was right for a three-button dialog and landed on "Splay comment"
// the day a fourth was added, which is exactly the sort of drift a screenshot does not show.
const ACT_X = 300
const EDIT_DISTANCE = [140, 384]
const COMMENT_FIELD_ABOVE_SAVE = 76
const COMMENT_SAVE_X = 317
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
// `action_bar.xml`'s submenus, which this port went back to when the flat list grew past the
// height of an iPhone SE. The top page opens one of the four groups or the About box; a group
// page is a Back row, then the group's items, with the saved surveys inside File where the app's
// own Open is.
const MENU_TOP = ['file', 'view', 'instrument', 'settings', 'about']
// `holdsSurveys` matters: only File grows with the library, and counting the surveys into the
// other three pages put every row of them out by one per saved survey — which lands a tap outside
// the menu, dismisses it, and reports itself several checks later as "no menu is open".
const MENU_PAGES = {
  file: { before: ['new', 'rename'], after: ['import', 'export'], holdsSurveys: true },
  view: { before: ['demo', 'trip', '3d', 'stats'], after: [], holdsSurveys: false },
  instrument: { before: ['connect', 'calibrate', 'log'], after: [], holdsSurveys: false },
  settings: { before: ['fullscreen', 'surveying', 'dark'], after: [], holdsSurveys: false },
}

/** Which page a named item is on, and where in it. Back is row zero of every group page. */
function menuPlace(name, savedSurveys) {
  const top = MENU_TOP.indexOf(name)
  if (top >= 0) return { page: null, index: top, rows: MENU_TOP.length }
  for (const [page, { before, after, holdsSurveys }] of Object.entries(MENU_PAGES)) {
    const surveys = holdsSurveys ? savedSurveys : 0
    const rows = 1 + before.length + surveys + after.length
    const inBefore = before.indexOf(name)
    if (inBefore >= 0) return { page, index: 1 + inBefore, rows }
    const inAfter = after.indexOf(name)
    if (inAfter >= 0) {
      return { page, index: 1 + before.length + surveys + inAfter, rows }
    }
    // The saved surveys themselves, addressed by position rather than by name.
    if (name === `${page}:survey`) return { page, index: 1 + before.length, rows }
  }
  throw new Error(`no menu item called ${name}`)
}

/**
 * Tap a named overflow-menu item, opening its group first if it is on one.
 *
 * The menu must already be open. Returns the coordinates of the final tap rather than performing
 * it, so the call sites read as they did before the menu grew a second level.
 */
async function menuRow(name, savedSurveys) {
  const place = menuPlace(name, savedSurveys)
  if (place.page !== null) {
    const group = menuPlace(place.page, savedSurveys)
    await at(...(await menuRowAt(group.index, group.rows, 312)))
    await page.waitForTimeout(500)
  }
  return menuRowAt(place.index, place.rows, 312)
}

/** The delete cross on the nth saved survey's row, which sits at the right-hand edge. */
async function savedSurveyDelete(nth, savedSurveys) {
  const place = menuPlace('file:survey', savedSurveys)
  const group = menuPlace('file', savedSurveys)
  await at(...(await menuRowAt(group.index, group.rows, 312)))
  await page.waitForTimeout(500)
  return menuRowAt(place.index + nth, place.rows, 392)
}
const IMPORT_CHOOSE = [284, 494]
const IMPORT_FIRST_ROW = [210, 446]
// The Surveying dialog's rows, measured from the top of the dialog rather than from the top of the
// screen.
//
// An AlertDialog is vertically centred, so *every* row in it moves by half the height of anything
// added to it — which is how adding two switches silently retyped the angle tolerance into the
// repeat count and left the dialog open over everything the checks did next. Anchoring to the
// dialog's own top means adding a setting is one number here (the height) plus one row, instead of
// six numbers that all have to be re-measured.
const SETTINGS_DIALOG_HEIGHT = 830
const settingsRow = (x, fromTop) => [x, (900 - SETTINGS_DIALOG_HEIGHT) / 2 + fromTop]
const SETTING_DISTANCE = settingsRow(210, 271)
const SETTING_ANGLE = settingsRow(210, 347)
const SETTING_BUZZ = settingsRow(320, 503)
const SETTING_HOT_CORNERS = settingsRow(320, 596)
const SETTING_TWO_FINGER = settingsRow(320, 692)
const SETTINGS_SAVE = settingsRow(317, 782)
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
  'find',
  'delete-last-leg',
  'display',
]
// The twelve toggles behind that last row, in the order the dialog lists them: `drawing.xml`'s
// display group first, then its behaviour group.
const DRAWING_OPTIONS = [
  'fade',
  'splays',
  'show-xsections',
  'sketch',
  'grid',
  'labels',
  'north',
  'latest-leg',
  'auto-recentre',
  'snap',
  'blue-water',
  'pinch',
]
// Material 3's `surfaceContainer` in the light theme, which is the dropdown's own ground and is
// not used by anything behind it.
const DRAWING_MENU_SURFACE = [243, 237, 247]

/**
 * Where a dropdown menu actually is, found rather than assumed. Used for both of them.
 *
 * It used to be computed upwards from a fixed bottom row, on the reasoning that a menu opening
 * from a toolbar cell keeps its bottom edge. That held until the menu grew past the room above the
 * toolbar: at eighteen rows it was taller than the gap, so Compose repositioned the whole thing to
 * fit the screen and *every* row moved — which broke ten checks at once and none of them anywhere
 * near a menu. Same lesson as the dialogs (finding 27), learnt twice. The menu is eight rows now,
 * which fits, but the arithmetic stays found rather than assumed: that is what made it survive the
 * twelve toggles moving out of it without a single number in this file changing.
 *
 * So: find the menu's own surface, and divide it by the number of rows it is known to have. That
 * survives the menu being repositioned, and it fails loudly rather than quietly if the menu ever
 * has to scroll, because then the arithmetic stops matching what is on screen.
 *
 * The overflow menu uses it too. That one is three rows plus the saved surveys plus eleven, so on
 * a 900-pixel screen it is already within a survey or two of not fitting under a fixed first-row
 * y — which is the same thing that happened to the drawing menu, waiting to happen again.
 */
const menuBox = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, surface]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const is = (x, y) => {
      const i = (y * c.width + x) * 4
      return px[i] === surface[0] && px[i + 1] === surface[1] && px[i + 2] === surface[2]
    }
    let top = -1
    let bottom = -1
    for (let y = 0; y < c.height; y++) {
      let run = 0
      for (let x = 0; x < c.width; x++) if (is(x, y)) run++
      // A wide run of it: narrower things on screen share the colour here and there.
      if (run > 120) {
        if (top < 0) top = y
        bottom = y
      }
    }
    return top < 0 ? null : { top, bottom }
  }, [b64, DRAWING_MENU_SURFACE])
}

/** The nth row of a menu with `rows` rows, wherever Compose has put it. */
async function menuRowAt(index, rows, x) {
  const menu = await menuBox()
  if (menu === null) throw new Error('no menu is open')
  const rowHeight = (menu.bottom - menu.top) / rows
  return [x, Math.round(menu.top + (index + 0.5) * rowHeight)]
}

/** The row for a named drawing-menu item, in the menu as it is currently drawn. */
async function drawingMenuRow(name) {
  const index = DRAWING_MENU.indexOf(name)
  if (index < 0) throw new Error(`no drawing-menu item called ${name}`)
  return menuRowAt(index, DRAWING_MENU.length, 186)
}

/**
 * The switches in the drawing-options dialog, found by looking for them.
 *
 * The rows are not evenly spaced — two headings and a divider sit among them — so dividing the
 * card by a row count would land between rows. What every row does have is a switch at its right
 * edge, and a switch is the one thing in that margin wide enough to leave a long horizontal run of
 * something that is not the dialog's own surface. So: find the card, look down its right-hand
 * margin for runs of at least thirty such pixels, and each band of them is one row.
 *
 * Which also means the test asserts the dialog has twelve rows without being told where they are.
 */
const drawingOptionSwitches = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const isCard = (x, y) => {
      const i = (y * c.width + x) * 4
      return px[i] === card[0] && px[i + 1] === card[1] && px[i + 2] === card[2]
    }
    let left = c.width
    let right = -1
    let top = -1
    let bottom = -1
    for (let y = 0; y < c.height; y++) {
      for (let x = 0; x < c.width; x++) {
        if (!isCard(x, y)) continue
        if (x < left) left = x
        if (x > right) right = x
        if (top < 0) top = y
        bottom = y
      }
    }
    if (right < 0) return null
    // Only the right-hand margin: the labels run across the rest of the row, and a word is a
    // scattering of short runs rather than one long one.
    const marginLeft = right - 70
    const bands = []
    let start = -1
    const close = (end) => {
      // Tall enough to be a switch. The divider between the two groups is a hairline that spans
      // the whole card, so it clears the width test and would otherwise count as a thirteenth
      // row — which is exactly how this was found.
      if (end - start >= 8) bands.push(Math.round((start + end) / 2))
      start = -1
    }
    for (let y = top; y <= bottom; y++) {
      let longest = 0
      let run = 0
      for (let x = marginLeft; x <= right; x++) {
        run = isCard(x, y) ? 0 : run + 1
        if (run > longest) longest = run
      }
      const onASwitch = longest >= 30
      if (onASwitch && start < 0) start = y
      if (!onASwitch && start >= 0) close(y - 1)
    }
    if (start >= 0) close(bottom)
    return { x: Math.round((left + right) / 2), bands }
  }, [b64, DIALOG_CARD])
}

/** Where to tap for a named drawing option, with the dialog already open. */
async function drawingOptionRow(name) {
  const index = DRAWING_OPTIONS.indexOf(name)
  if (index < 0) throw new Error(`no drawing option called ${name}`)
  const found = await drawingOptionSwitches()
  if (found === null) throw new Error('the drawing-options dialog is not open')
  if (found.bands.length !== DRAWING_OPTIONS.length) {
    throw new Error(
      `the drawing-options dialog has ${found.bands.length} rows, not ` +
        `${DRAWING_OPTIONS.length} (at ${found.bands.join(', ')})`)
  }
  return [found.x, found.bands[index]]
}

/**
 * Flip one drawing option: open the menu, open the dialog, tap the row, close the dialog.
 *
 * Four taps where it used to be two, which is the price of the split and is paid by the test far
 * more often than by a surveyor: these are settings somebody changes once a trip, and the two taps
 * they lost bought them a menu that fits on the screen.
 */
async function toggleOption(name) {
  await at(...toolCell(5)); await page.waitForTimeout(500)
  await at(...(await drawingMenuRow('display'))); await page.waitForTimeout(700)
  await at(...(await drawingOptionRow(name))); await page.waitForTimeout(500)
  await page.keyboard.press('Escape'); await page.waitForTimeout(600)
}

/**
 * Where the export screen's format chips are, found rather than counted.
 *
 * They are a FlowRow, so adding one format reflows every chip after it and can push a whole row
 * down. Adding the `.thconfig` did exactly that: the old hard-coded "second row, middle chip" then
 * pointed at *Tracing .xvi*, and the check went on passing — against the wrong format — until the
 * filename it asserted disagreed. Save file moved fifty pixels down at the same time and landed on
 * a chip.
 *
 * A chip is the only thing drawn on this screen's background, so its top edge is a long horizontal
 * run of not-the-background: an outline for an unselected chip, a filled one for the selected one.
 * The row's top edge shows every chip in the row; the rows below it show only the filled one, so
 * the y with the most runs in each band is the top edge and the runs on it are the chips.
 */
const exportChips = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, background]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const isBackground = (x, y) => {
      const i = (y * c.width + x) * 4
      return Math.abs(px[i] - background[0]) < 6 &&
        Math.abs(px[i + 1] - background[1]) < 6 &&
        Math.abs(px[i + 2] - background[2]) < 6
    }
    const runsAcross = (y) => {
      const found = []
      let start = null
      for (let x = 0; x < c.width; x++) {
        const bg = isBackground(x, y)
        if (!bg && start === null) start = x
        if (bg && start !== null) {
          if (x - start >= 30) found.push([start, x - 1])
          start = null
        }
      }
      if (start !== null && c.width - start >= 30) found.push([start, c.width - 1])
      return found
    }
    // Below the app bar, above the exported text: the chips live in that band and nothing else
    // wide enough to make a run does.
    //
    // Grouped by how far apart the y's are rather than by whether there is a gap between them: a
    // row shows its chips at its top edge *and* again at its bottom edge, and the two are not
    // contiguous when no chip in that row is the selected one. Gap-banding therefore found seven
    // rows in four, and every chip twice.
    //
    // Forty is the threshold because a chip is 32 tall and the rows are 50 apart, so it is the
    // only number that separates a row from the next one without splitting a row in half.
    const rows = []
    for (let y = 56; y < 280; y++) {
      const found = runsAcross(y)
      if (found.length === 0) continue
      const row = rows[rows.length - 1]
      if (row && y - row.top <= 40) {
        row.bottom = y
        if (found.length > row.runs.length) row.runs = found
      } else {
        rows.push({ top: y, bottom: y, runs: found })
      }
    }
    // A chip is 32 tall, so its top and bottom edges are 31 apart. A line of text is a third of
    // that — which is what the "Saved to ..." line under the buttons turned out to be, appearing
    // as a tenth chip only *after* the first save in the run and only in that one check.
    return rows
      .filter((row) => row.bottom - row.top >= 24)
      .flatMap((row) =>
        row.runs.map(([from, to]) => [Math.round((from + to) / 2), row.top + 16]))
  }, [b64, EXPORT_BACKGROUND])
}

/** Where to tap for a named export format, with the export screen showing. */
async function exportChip(name) {
  const index = EXPORT_FORMATS.indexOf(name)
  if (index < 0) throw new Error(`no export format called ${name}`)
  const chips = await exportChips()
  if (chips.length !== EXPORT_FORMATS.length) {
    throw new Error(
      `the export screen shows ${chips.length} chips, not ${EXPORT_FORMATS.length}` +
        ` (at ${chips.map((chip) => chip.join(',')).join(' ')})`)
  }
  return chips[index]
}

/** Save file, which sits below the last row of chips however many rows there are. */
async function exportSaveFile() {
  const chips = await exportChips()
  const lowest = Math.max(...chips.map(([, y]) => y))
  return [117, lowest + 52]
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
const PALETTE_WATER = [112, 588]
const CANCEL_DELETE_SURVEY = [237, 516]
const CONFIRM_DELETE_SURVEY = [312, 516]
// The export screen's own background, which its chips are the only thing drawn on.
const EXPORT_BACKGROUND = [245, 245, 245]
// The chips, in the order ExportFormat declares them. They wrap, so which row a chip lands on
// depends on how wide the ones before it are and on how wide the phone is — see exportChips().
const EXPORT_FORMATS = [
  'svx',
  'th',
  'thconfig',
  'svg',
  'xvi',
  'th2',
  'dat',
  'txt',
  'json',
]
// The cross-section editor's own bar: Cancel at the left, Done at the right.
const EDITOR_CANCEL = [46, 24]
// The station menu's first action row. Measured once from the rendered dialog, like the settings
// screen above it; the dialog is centred, so adding an action moves every row.
// The station menu's rows are found rather than hard-coded. A dialog is centred, so its rows move
// with its height — and this dialog's height depends on the station: the origin has no incoming
// leg and cannot be deleted, so it is two rows shorter than one in the middle of a passage. A
// fixed y worked for whichever station happened to be tested and silently clicked the wrong item
// for any other.
const DIALOG_CARD = [236, 230, 240]
const DIALOG_FIRST_ROW_FROM_TOP = 96

/**
 * The y of every band of primary-coloured text inside the dialog card — its clickable rows.
 *
 * Material draws a TextButton's label in the primary colour and nothing else on the card uses it,
 * so a row that can be tapped is a row with purple in it. Finding the rows rather than counting
 * fixed heights from the top is what makes a check survive a dialog whose contents depend on the
 * survey: the station menu is two rows shorter for the origin than for a station mid-passage.
 */
const dialogTextRows = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // Material 3's default primary, 0x6750A4, and the antialiased shades of it: blue well above
    // green is the signature, and nothing else on the card has it — the title is black, the field
    // label and its outline grey, the card itself a pale lavender with the three channels close
    // together.
    //
    // Counted with a threshold of one pixel per scan line rather than a handful, because a station
    // is called "1": a single numeral four pixels wide, which a threshold tuned on the word
    // "Close" misses entirely and reports as a dialog with no stations in it.
    const isLabel = (x, y) => {
      const i = (y * c.width + x) * 4
      const [r, g, b] = [px[i], px[i + 1], px[i + 2]]
      return b > r && r > g && b - g > 30 && b < 230
    }
    // Bounded to the card. The app's own screen shows through around a dialog, and the field bar's
    // "Add reading" button and the toolbar's purple swatch are the same primary colour — an
    // unbounded scan reports them as two more rows of the dialog, at the bottom of the screen.
    const isCard = (x, y) => {
      const i = (y * c.width + x) * 4
      return (
        Math.abs(px[i] - card[0]) < 4 &&
        Math.abs(px[i + 1] - card[1]) < 4 &&
        Math.abs(px[i + 2] - card[2]) < 4
      )
    }
    let top = -1
    let bottom = -1
    for (let y = 0; y < c.height; y++) {
      let cardPixels = 0
      for (let x = 0; x < c.width; x++) if (isCard(x, y)) cardPixels++
      if (cardPixels > c.width * 0.5) {
        if (top < 0) top = y
        bottom = y
      }
    }
    if (top < 0) return []

    const rows = []
    let from = -1
    for (let y = top; y <= bottom; y++) {
      let count = 0
      for (let x = 70; x < c.width - 60; x++) if (isLabel(x, y)) count++
      if (count >= 1) {
        if (from < 0) from = y
      } else if (from >= 0) {
        if (y - from >= 6) rows.push(Math.round((from + y) / 2))
        from = -1
      }
    }
    return rows
  }, [b64, DIALOG_CARD])
}

/**
 * The clickable rows of the leg menu, top to bottom, as [x, y] pairs ready for `at`.
 *
 * Row 0 is "Edit reading" — "Close" sits on the same line, further left, so a click at [ACT_X, y]
 * lands on the action rather than the dismissal. Every label in the column is right-aligned to the
 * same edge, so one x works for "Delete" and for "Add it to the leg above" alike.
 */
const legActionRows = async () => (await dialogTextRows()).map((y) => [ACT_X, y])

const dialogTop = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const isCard = (x, y) => {
      const i = (y * c.width + x) * 4
      return (
        Math.abs(px[i] - card[0]) < 4 &&
        Math.abs(px[i + 1] - card[1]) < 4 &&
        Math.abs(px[i + 2] - card[2]) < 4
      )
    }
    // The first row that is *mostly* card, rather than a long unbroken run down one column: the
    // dialog's title sits a couple of dozen pixels below its top edge, so a column scan through
    // the middle sees a run far shorter than a dialog with a paragraph in it, and a threshold
    // tuned on one of them rejects the other. Counting across the row does not care what is
    // written on the card.
    for (let y = 0; y < c.height; y++) {
      let count = 0
      for (let x = 0; x < c.width; x++) if (isCard(x, y)) count++
      if (count > c.width * 0.5) return y
    }
    return null
  }, [b64, DIALOG_CARD])
}

/** How tall the dialog card is, or null if there is none. */
const dialogHeight = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let top = -1
    let bottom = -1
    for (let y = 0; y < c.height; y++) {
      let count = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (
          Math.abs(px[i] - card[0]) < 4 &&
          Math.abs(px[i + 1] - card[1]) < 4 &&
          Math.abs(px[i + 2] - card[2]) < 4
        ) count++
      }
      if (count > c.width * 0.5) {
        if (top < 0) top = y
        bottom = y
      }
    }
    return top < 0 ? null : bottom - top
  }, [b64, DIALOG_CARD])
}

/** How far in from the right the dialog card's edge is, or null if there is none. */
const dialogRight = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let right = -1
    for (let y = 0; y < c.height; y++) {
      for (let x = c.width - 1; x > right; x--) {
        const i = (y * c.width + x) * 4
        if (
          Math.abs(px[i] - card[0]) < 4 &&
          Math.abs(px[i + 1] - card[1]) < 4 &&
          Math.abs(px[i + 2] - card[2]) < 4
        ) { right = x; break }
      }
    }
    return right < 0 ? null : right
  }, [b64, DIALOG_CARD])
}

/**
 * The confirm button, which Material puts at the bottom right of the card.
 *
 * The x is measured off the card rather than fixed, because the card is narrower on a smaller
 * screen: a value tuned at 420 pixels wide missed the button entirely at 375 and the check read
 * as the button not working.
 */
const dialogConfirm = async () => {
  const rows = await dialogTextRows()
  if (rows.length === 0) return null
  const right = await dialogRight()
  return right === null ? null : [right - 30, rows[rows.length - 1]]
}


// The 3D view's own bar: Close at the left, Reset at the right.
const THREE_D_CLOSE = [42, 24]
const EDITOR_DONE = [382, 24]

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
await at(...(await menuRow('new', 0))); await page.waitForTimeout(700)
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
await at(...(await drawingMenuRow('cross-section'))); await page.waitForTimeout(500)
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
await at(...(await drawingMenuRow('move'))); await page.waitForTimeout(500)
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
await at(...(await drawingMenuRow('re-aim'))); await page.waitForTimeout(500)
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

// ---- and taken off the drawing altogether ------------------------------------------------
// `sketch_menu_show_xsections`. The half worth checking is not that they stop being drawn but that
// they stop being *tapped*: the Android app's own "special case: can't tap on invisible
// X-sections". A port that hid them and left the hit test live would open an editor from a tap on
// what looks like blank paper, which is the kind of thing nobody reports because nobody believes
// it happened.
//
// Nothing here changes the survey or the drawing. An earlier version of this check proved the tap
// by drawing a stroke and seeing which sketch it landed in, and left that stroke behind for the
// four checks that follow to trip over.
const inkAround = async (span) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, span]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let ink = 0
    for (let y = span[1]; y < span[3]; y++) {
      for (let x = span[0]; x < span[2]; x++) {
        const i = (y * c.width + x) * 4
        // Anything darker than the paper. The window is small enough to hold nothing else.
        const lightest = Math.max(px[i], px[i + 1], px[i + 2])
        if (lightest < 215) ink += 215 - lightest
      }
    }
    return ink
  }, [b64, span])
}

// A tight box round where the section now sits, clear of station 1, of the leg and of the metre
// grid. Tight because at this point in the run the section is only its centre dot: it was dropped
// at a station whose wall shots are not booked until much later, so it has no arms to draw yet,
// and the whole of it is twenty pixels across.
const SECTION_PATCH = [194, 652, 214, 676]
// The purple of the "Add reading" pill, sampled off the pill rather than off the white lettering
// across the middle of it.
const FIELD_BAR_PILL = [40, 790]

const withSection = await inkAround(SECTION_PATCH)
await toggleOption('show-xsections')
await page.screenshot({ path: join(shotDir, 'field-cross-section-hidden.png') })

const withoutSection = await inkAround(SECTION_PATCH)
if (!(withSection > 100 && withoutSection === 0)) {
  fail(`hiding cross-sections left the drawing much as it was (${withSection} then ${withoutSection})`)
} else {
  pass('a cross-section can be taken off the drawing when it is in the way')
}

// A tap where it is must do nothing at all. The editor takes the whole screen, so the plan's own
// field bar still being there is what says it did not open.
const fieldBarIsShowing = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, at]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const i = (at[1] * c.width + at[0]) * 4
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // The "Add reading" pill: Material's primary, which nothing in the editor puts here.
    return px[i + 2] > px[i] && px[i] > px[i + 1] && px[i + 2] - px[i + 1] > 30
  }, [b64, FIELD_BAR_PILL])
}

await at(210, 660); await page.waitForTimeout(900)
if (!(await fieldBarIsShowing())) {
  fail('a tap on a hidden cross-section opened its editor anyway')
} else {
  pass('and while it is hidden a tap goes straight through it')
}

// Back on, because everything after this expects the app's own default.
await toggleOption('show-xsections')

// ---- and drawn into --------------------------------------------------------------------
// A star of splays is not a passage; the outline drawn round it is what makes it one. Tapping a
// section opens its own editor, exactly as the Android app's does from any tool but pan and erase.
await at(...toolCell(1)); await page.waitForTimeout(400)
await at(210, 660); await page.waitForTimeout(1000)
await page.screenshot({ path: join(shotDir, 'field-cross-section-editor.png') })

const subSketchPaths = async () => {
  const section = await firstSection()
  return ((section?.sketch ?? {}).paths ?? []).length
}
const planPaths = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  return key ? (JSON.parse(localStorage.getItem(key)).paths ?? []).length : -1
})

// Draw the passage outline and keep it.
await drag([120, 400], [300, 400]); await page.waitForTimeout(400)
await drag([300, 400], [300, 560]); await page.waitForTimeout(400)
await page.screenshot({ path: join(shotDir, 'field-cross-section-drawn.png') })
await at(...EDITOR_DONE); await page.waitForTimeout(1000)
await page.screenshot({ path: join(shotDir, 'field-cross-section-done.png') })

if ((await subSketchPaths()) < 2) {
  fail('the passage outline drawn in the cross-section editor was not saved with the section')
} else {
  pass('the passage outline can be drawn inside a cross-section, and is saved with it')
}

// And now cancel one, which the editor can only honour because it draws into a copy.
//
// Both halves are asserted, and the second is what gives this teeth: if the editor had not opened
// at all, these strokes would have landed on the *plan* instead — which is exactly the failure the
// first version of this check could not tell apart from a working cancel.
const planPathsBefore = await planPaths()
await at(210, 660); await page.waitForTimeout(1000)
await drag([120, 300], [300, 300]); await page.waitForTimeout(400)
await at(...EDITOR_CANCEL); await page.waitForTimeout(1000)

if ((await subSketchPaths()) !== 2) {
  fail('cancelling the cross-section editor kept the stroke anyway')
} else if ((await planPaths()) !== planPathsBefore) {
  fail('the cross-section editor never opened — the stroke went onto the plan instead')
} else {
  pass('a stroke abandoned in the cross-section editor leaves both the section and the plan alone')
}

// Back to drawing, so nothing after this drops a section by accident.
await at(...toolCell(1)); await page.waitForTimeout(400)

// ---- strokes can be made to join up ------------------------------------------------------
// A passage wall is drawn as a series of strokes, and a wall with gaps in it is one no tracing
// tool downstream can close. Snapping is off by default, as it is in the Android app, so this
// turns it on, draws two strokes that nearly meet, and checks the second starts exactly where the
// first ended rather than near it.
await toggleOption('snap')

await drag([80, 250], [180, 250]); await page.waitForTimeout(500)
await drag([186, 256], [186, 340]); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-snapped.png') })

const strokeEnds = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key)).paths ?? []).map((p) => p.points)
})
if (strokeEnds === null || strokeEnds.length < 2) {
  fail(`two strokes were not saved, so snapping could not be checked (${strokeEnds?.length})`)
} else {
  const [first, second] = strokeEnds.slice(-2)
  const end = first[first.length - 1]
  const start = second[0]
  if (end.x !== start.x || end.y !== start.y) {
    fail(`the second stroke did not snap to the first (${JSON.stringify(end)} vs ${JSON.stringify(start)})`)
  } else {
    pass('strokes snap to each other, so a passage wall drawn in pieces has no gaps in it')
  }
}

// Off again, so nothing later in this file is silently snapped.
await toggleOption('snap')

// ---- the drawing can be moved without putting the pencil down ----------------------------
// The single most frequent thing a surveyor does to a sketch is move it, and until the hot corners
// existed the only way to do that while drawing was MOVE, drag, DRAW again: two toolbar taps per
// pan, on a phone, in a wet oversuit. A touch that starts in any corner pans instead of marking.
//
// The corners are found rather than hard-coded, by scanning the left-hand edge of the sketch for
// the two faint squares. That locates the sketch surface itself — which sits between an app bar
// and two rows of toolbar, so its own top edge is not a number worth writing down — and it doubles
// as the check that the corners are actually drawn. The Android app tests four corners and tints
// three; the one scanned for here is the bottom-left, the corner its own `drawHotCorners` leaves
// invisible.
const planStrokes = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  return key ? (JSON.parse(localStorage.getItem(key)).paths ?? []).length : -1
})

const cornerRunsAt = async (column) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, x]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // Grey 0x808080 at a fifth alpha over the app's own 0xF5F5F5 canvas: 222 on every channel.
    // The grid is 211 and only ever one pixel tall, so a run of a dozen rows is unambiguous.
    const isCorner = (y) => {
      const i = (y * c.width + x) * 4
      const [r, g, b] = [px[i], px[i + 1], px[i + 2]]
      return r >= 214 && r <= 232 && Math.abs(r - g) < 4 && Math.abs(g - b) < 4
    }
    const runs = []
    let from = -1
    for (let y = 0; y <= c.height; y++) {
      if (y < c.height && isCorner(y)) { if (from < 0) from = y } else {
        if (from >= 0 && y - from >= 12) runs.push({ top: from, height: y - from })
        from = -1
      }
    }
    return runs
  }, [b64, column])
}

const leftCorners = await cornerRunsAt(2)
const rightCorners = await cornerRunsAt(Math.round(box.width) - 3)

// The *height* of the lower run is not the corner's: the field bar below the sketch is painted
// 221, one away from the corner's 222, so the two touch and the scan reads them as one. Its top
// is still the corner's top, and the upper run — bounded above by the green app bar — gives the
// side. Taking the side from the merged run instead put the drag on the "Add reading" button,
// where it did nothing and the check passed anyway, which is the failure mode worth a comment.
const cornerSide = leftCorners[0]?.height ?? 0
const cornerCentres = (runs, x) =>
  runs.map((run) => [x, Math.round(run.top + cornerSide / 2)])

if (leftCorners.length !== 2 || rightCorners.length !== 2) {
  fail(
    'the sketch\'s hot corners were not drawn ' +
      `(${leftCorners.length} down the left edge, ${rightCorners.length} down the right)`,
  )
} else if (leftCorners[0].top !== rightCorners[0].top) {
  fail('the top corners are not level with each other')
} else {
  pass('the corners that pan the sketch are drawn, all four of them')

  // The bottom-left one, which is the corner the Android app tests and never tints.
  const from = cornerCentres(leftCorners, Math.round(cornerSide / 2))[1]
  const strokesBefore = await planStrokes()
  const beforePanning = await page.screenshot({ clip: box })

  await drag(from, [from[0] + 120, from[1] - 90]); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-hot-corner-pan.png') })

  const afterPanning = await page.screenshot({ clip: box })
  const strokesAfter = await planStrokes()
  if (strokesAfter !== strokesBefore) {
    fail(`a drag from a corner drew on the survey (${strokesBefore} strokes became ${strokesAfter})`)
  } else if (Buffer.compare(beforePanning, afterPanning) === 0) {
    fail('a drag from a corner moved nothing — the pencil cannot be put down without the toolbar')
  } else {
    pass('a drag from a corner pans the drawing instead of marking it, with the pencil still down')
  }

  // And the pencil still works everywhere else, which is the other half of the bargain: a canvas
  // that panned on every touch would pass the check above and be useless.
  await drag([150, from[1] - 200], [260, from[1] - 200]); await page.waitForTimeout(700)
  if ((await planStrokes()) !== strokesBefore + 1) {
    fail('after a corner pan the draw tool stopped drawing')
  } else {
    pass('the draw tool is still the draw tool afterwards — no toolbar round trip needed')
  }
}

// Put the view back where it was, so nothing after this depends on where the corner pan left it.
await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...(await drawingMenuRow('centre'))); await page.waitForTimeout(700)

// ---- any station can be got at, not just the active one -----------------------------------
// Until the long-press menu existed, the only station a surveyor could name, comment or measure
// was the *active* one, through the chip on the field bar. That is fine while the survey is being
// pushed forward and useless the moment somebody wants to go back and write "sump" on a junction
// they passed twenty minutes ago.
//
// The station to hold is found rather than guessed, from two things the app draws in colours
// nothing else uses: the amber brackets round the active station, and the pure red of the
// centreline. The far end of the centreline from the brackets is a station, and it is not the
// active one — which is the whole point of the check.
//
// Two earlier attempts at this looked simpler and tested the wrong pixel. "The darkest-red pixel
// furthest from the amber" found the brush palette's dark-red swatch in the toolbar; bounding the
// scan to the sketch then found the *cross-section* dropped on the plan a few checks earlier,
// which is drawn in the station colour too. Both times the press landed somewhere that is not a
// station, the menu did not open, and the check that no stroke appeared passed anyway, because
// neither a paint swatch nor a cross-section draws one.
const stationSpots = async (fromY, toY) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, top, bottom]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const centreline = []
    const amber = []
    for (let y = top; y < bottom; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        const [r, g, b] = [px[i], px[i + 1], px[i + 2]]
        // `leg`: pure red, or the magenta the reading just taken is drawn in — which on a
        // two-station survey is the only leg there is, so insisting on red found nothing at all.
        // Splays are salmon, stations and cross-sections a dark red, so a red channel this high
        // with green this low is the centreline and nothing else.
        if (r > 230 && g < 60 && (b < 60 || b > 230)) centreline.push([x, y])
        // `activeStationHighlight`: 0xFFC107.
        if (r > 230 && g > 170 && g < 215 && b < 60) amber.push([x, y])
      }
    }
    const mean = (points) => points.length === 0 ? null
      : [Math.round(points.reduce((a, p) => a + p[0], 0) / points.length),
         Math.round(points.reduce((a, p) => a + p[1], 0) / points.length)]
    const active = mean(amber)
    if (!active || centreline.length === 0) return { active, other: null }

    let best = null
    let bestDistance = -1
    for (const [x, y] of centreline) {
      const d = (x - active[0]) ** 2 + (y - active[1]) ** 2
      if (d > bestDistance) { bestDistance = d; best = [x, y] }
    }
    return { active, other: Math.sqrt(bestDistance) > 60 ? best : null }
  }, [b64, fromY, toY])
}

/**
 * Whether the table is what is on screen, asked of the toolbar rather than of the table.
 *
 * The sketch toolbar is two rows of the app's own green and the table has none, so the bottom of
 * the screen answers it in one colour. Looking for something the table *has* would mean matching
 * its header against the app bar, which is the same Material role at the other end of the screen.
 */
const onTheTable = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, panel]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let green = 0
    for (let y = c.height - 120; y < c.height; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] === panel[0] && px[i + 1] === panel[1] && px[i + 2] === panel[2]) green++
      }
    }
    return green < 200
  }, [b64, SKETCH_PANEL])
}

/** Press and hold, which is how the Android app reaches a station's menu. */
async function longPress([x, y]) {
  await page.mouse.move(box.x + x, box.y + y)
  await page.mouse.down()
  await page.waitForTimeout(900)
  // The menu opens on the release, not on the hold — see the note in `detectLongPress` about a
  // dialog put up under a finger that has not lifted yet.
  await page.mouse.up()
  await page.waitForTimeout(700)
}

// The sketch surface, from the corner squares found earlier: its top edge is the first one's top,
// and its bottom edge is where the lower one ends.
const sketchTop = leftCorners[0]?.top ?? 0
const sketchBottom = (leftCorners[1]?.top ?? Math.round(box.height)) + cornerSide
const spots = await stationSpots(sketchTop, sketchBottom)
if (!spots.other) {
  fail(`could not find a station on the plan that is not the active one (${JSON.stringify(spots)})`)
} else {
  const activeBefore = await page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
    return key ? JSON.parse(localStorage.getItem(key)).activeStation : null
  })
  const strokesBefore = await planStrokes()

  await longPress(spots.other)
  await page.screenshot({ path: join(shotDir, 'field-station-menu.png') })

  const menuTop = await dialogTop()
  if (menuTop === null) {
    fail(`holding a station did not open its menu (pressed ${JSON.stringify(spots.other)})`)
    // A press that did not become a long press is a plain tap, and a tap near a cross-section
    // opens its editor over everything that follows — so back out of whatever did happen.
    await page.keyboard.press('Escape'); await page.waitForTimeout(400)
    await at(...EDITOR_CANCEL); await page.waitForTimeout(600)
  } else {
    // The pencil is down: holding still must open the menu rather than leave a dot behind.
    if ((await planStrokes()) !== strokesBefore) {
      fail('holding a station with the draw tool active left a mark on the sketch')
    } else {
      pass('a station can be held down while drawing without marking the paper')
    }

    // The first row, which for a station that is not the active one is "Start the next leg here".
    await at(210, menuTop + DIALOG_FIRST_ROW_FROM_TOP); await page.waitForTimeout(900)
    const activeAfter = await page.evaluate(() => {
      const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
      return key ? JSON.parse(localStorage.getItem(key)).activeStation : null
    })
    if (activeAfter === activeBefore) {
      fail(`the station menu did not move the active station (still ${activeAfter})`)
    } else {
      pass(
        'any station can be reached from the sketch, not just the active one ' +
          `(${activeBefore} -> ${activeAfter})`,
      )
    }
  }
}

// ---- and the sketch can send you back to the table -----------------------------------------
// The other half of `menu_navigate`: each station menu offers the two views you are *not* looking
// at. The table's has offered "show it on the plan" for a while; the sketch's offered nothing, so
// a surveyor who spotted a suspicious station on the drawing had to go to the table and find the
// row themselves.
//
// What this checks is the half that is visible: choosing it puts you on the table. *Which* row it
// scrolls to is `rowIndexFor`, which has its own tests — this survey is short enough that the
// table does not scroll at all, and a check that cannot fail is worse than no check.
await longPress(spots.other ?? spots.active)

const jumpMenuTop = await dialogTop()
if (jumpMenuTop === null) {
  fail('holding a station a second time did not open its menu')
} else {
  // The first row. "Start the next leg here" is offered only for a station that is *not* the
  // active one, and the check above has just made this one active — so it is gone and "Show it in
  // the table" is at the top. If that reasoning were wrong this would tap the row below it and
  // open the station's own dialog, which is not the table: the check fails rather than passes.
  const rows = await dialogTextRows()
  await at(210, rows[0]); await page.waitForTimeout(1000)
  await page.screenshot({ path: join(shotDir, 'field-jumped-to-table.png') })

  if (!(await onTheTable())) {
    fail('"show it in the table" did not take the surveyor to the table')
  } else {
    pass('a station held on the drawing can send you to its row in the table')
  }
  await at(...PLAN_TAB); await page.waitForTimeout(700)
}

// ---- finding a station, and taking back the last leg ---------------------------------------
// Two things a surveyor does constantly and this port could not do at all. A survey of any size
// does not fit on a phone screen, so "where is the station I stopped at" had no answer but pinching
// out until the whole cave fits — which is exactly the zoom at which the labels are too small to
// read. And a leg taken from the wrong station wants to be gone before the next one goes in, which
// through the table is three taps and a scroll away from where the surveyor is standing.
const savedLegCount = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  if (!key) return -1
  return (JSON.parse(localStorage.getItem(key)).stations ?? []).flatMap((s) => s.legs ?? []).length
})

const beforeFinding = await page.screenshot({ clip: box })
await at(...toolCell(5)); await page.waitForTimeout(500)
await at(...(await drawingMenuRow('find'))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-find-station.png') })

if ((await dialogTop()) === null) {
  fail('the find-a-station dialog did not open')
} else {
  const rows = await dialogTextRows()
  // The last row is the dialog's own Close button; the ones above it are the stations.
  if (rows.length < 2) {
    fail(`the find dialog listed no stations (${rows.length} rows)`)
    await page.keyboard.press('Escape'); await page.waitForTimeout(400)
  } else {
    await at(210, rows[0]); await page.waitForTimeout(900)
    const afterFinding = await page.screenshot({ clip: box })
    if (Buffer.compare(beforeFinding, afterFinding) === 0) {
      fail('going to a station left the view exactly where it was')
    } else {
      pass('a station can be found by name and the view taken to it')
    }
  }
}

// Taken back on a splay added for the purpose, so the survey the checks below read is the one they
// were written for.
const legsBeforeSplay = await savedLegCount()
await reading(2.5, 45, -3, { splay: true })
if ((await savedLegCount()) !== legsBeforeSplay + 1) {
  fail('the splay that the delete was going to take back was not added')
} else {
  await at(...toolCell(5)); await page.waitForTimeout(500)
  await at(...(await drawingMenuRow('delete-last-leg'))); await page.waitForTimeout(900)
  await page.screenshot({ path: join(shotDir, 'field-delete-last-leg.png') })
  const confirm = await dialogConfirm()
  if ((await dialogTop()) === null || confirm === null) {
    fail('the delete-the-last-leg dialog did not open')
  } else {
    await at(...confirm); await page.waitForTimeout(900)
    const after = await savedLegCount()
    if (after !== legsBeforeSplay) {
      fail(`the last leg was not taken back (${legsBeforeSplay + 1} legs became ${after})`)
    } else {
      pass('the last leg can be taken back from the drawing menu, and only it')
    }
  }
}

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

  // What a splay is offered, and what it is not. A splay cannot be reversed — reverseLeg is
  // addressed by the station a leg arrives at, and a splay arrives nowhere — and it is already
  // what a downgrade would make it. Getting this wrong is not a cosmetic fault: every one of
  // these buttons rewrites the survey.
  const splayActions = await legActionRows()
  if (splayActions.length !== 5) {
    fail(`a splay offered ${splayActions.length} actions, not the expected five`)
  } else {
    pass('a splay is offered every way of promoting it, and neither of the leg-only actions')
  }

  await at(...splayActions[0]); await page.waitForTimeout(700)
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
  const toDelete = await legActionRows()
  await at(...toDelete[toDelete.length - 1]); await page.waitForTimeout(600)
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

// ---- a note against a leg, and a shot booked the wrong way round --------------------------
// Both are things a surveyor does at the moment they notice, and neither has anywhere else to
// live: the sketch has an undo stack and the survey does not, so a leg entered end-for-end can
// only be fixed by saying so. The comment is the other half — "sump; do not follow" is the one
// sentence on a trip that somebody's safety may turn on, and until now nothing in this port could
// write one against a leg.
const distanceInk = async (y) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, y]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // The Distance column of one table row, measured as how much darker than the paper it is
    // rather than as a count of pixels below a threshold. A dagger at 12sp is one hairline stem
    // and a crossbar: at this size almost every pixel of it is antialiased grey rather than ink,
    // so a threshold tuned on "5.420" scores the whole glyph as nothing and reports a table that
    // is showing the marker perfectly well as one that is not.
    let ink = 0
    for (let yy = y - 9; yy <= y + 9; yy++) {
      for (let x = 136; x < 232; x++) {
        const i = (yy * c.width + x) * 4
        if (px[i] < 200) ink += 200 - px[i]
      }
    }
    return ink
  }, [b64, y])
}

const inkBefore = await distanceInk(TABLE_ROW(1)[1])

await at(...TABLE_ROW(1)); await page.waitForTimeout(700)
const legActions = await legActionRows()
if (legActions.length !== 5) {
  fail(`a leg with nothing beyond it offered ${legActions.length} actions, not five`)
} else {
  pass('a leg with nothing surveyed beyond it can be taken back down to a splay')
}
await at(...legActions[1]); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-leg-comment.png') })
// One purple row, holding Cancel and Save; the field sits a fixed distance above it. Anchoring on
// the row rather than on the screen keeps this right if the dialog gains a line of explanation.
const commentSaveY = (await dialogTextRows()).pop()
if (commentSaveY === undefined) {
  fail('the leg comment dialog did not open')
} else {
  await at(210, commentSaveY - COMMENT_FIELD_ABOVE_SAVE); await page.waitForTimeout(250)
  await page.keyboard.type('sump; do not follow', { delay: 15 })
  await at(COMMENT_SAVE_X, commentSaveY); await page.waitForTimeout(900)
}

const commented = (await savedLegs()).find(isConnecting)
if (!commented || commented.comment !== 'sump; do not follow') {
  fail(`the leg comment did not reach the saved survey (${JSON.stringify(commented?.comment)})`)
} else {
  pass('a note can be written against a leg, and is saved with the survey')
}

// The Android app's own marker, and the only sign in the table that a comment exists at all. It
// leads the distance, so the cell gains ink it did not have.
await page.screenshot({ path: join(shotDir, 'field-leg-commented.png') })
const inkAfter = await distanceInk(TABLE_ROW(1)[1])
if (!(inkAfter > inkBefore)) {
  fail(`the table shows no marker against the commented leg (ink ${inkBefore} then ${inkAfter})`)
} else {
  pass('and the table marks the row, so a comment can be found without opening anything')
}

// ---- reversing it, and reversing it back ---------------------------------------------------
await at(...TABLE_ROW(1)); await page.waitForTimeout(700)
const reverseRow = (await legActionRows())[2]
await at(...reverseRow); await page.waitForTimeout(900)

const reversed = (await savedLegs()).find(isConnecting)
const legStations = async () => (await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? (JSON.parse(localStorage.getItem(key)).stations ?? []).map((st) => st.name) : []
}))
if (!reversed?.wasShotBackwards) {
  fail(`the leg was not recorded as shot from the far end (${JSON.stringify(reversed)})`)
} else if ((await legStations()).length !== 2) {
  fail('reversing the leg lost a station')
} else if (reversed.comment !== 'sump; do not follow') {
  fail('reversing the leg dropped its comment')
} else {
  pass('a shot booked the wrong way round can be turned, and keeps what was written on it')
}

await at(...TABLE_ROW(1)); await page.waitForTimeout(700)
await at(...(await legActionRows())[2]); await page.waitForTimeout(900)
const backAgain = (await savedLegs()).find(isConnecting)
if (backAgain?.wasShotBackwards) {
  fail('reversing the leg a second time did not put it back')
} else {
  pass('and turning it again puts it back, which is what makes it safe to try')
}

// ---- and a tap on a station's name is about the station ---------------------------------------
// The Android app has two station menus, and the table's is the one that offers to take you to the
// station on a drawing — which is the link between the two halves of the app. Scan the table, spot
// the reading that looks wrong, tap the station, and look at where it is.
const FROM_CELL_X = 25
const TO_CELL_X = 88

await at(TO_CELL_X, TABLE_ROW(1)[1]); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-table-station.png') })
const farEndRows = await dialogTextRows()
await page.keyboard.press('Escape'); await page.waitForTimeout(500)

await at(FROM_CELL_X, TABLE_ROW(1)[1]); await page.waitForTimeout(700)
const nearEndRows = await dialogTextRows()

// The two ends of one leg offer different menus, which is the whole point of asking the column
// which station it shows: the far station can be made active, has a leg that got to it and can be
// deleted; the origin can do none of those three.
if (farEndRows.length !== nearEndRows.length + 3) {
  fail(
    `the two ends of the leg offered ${nearEndRows.length} and ${farEndRows.length} rows, ` +
      'which is not the three-item difference between the origin and a station beyond it',
  )
} else {
  pass('a tap on a station\'s name opens that station\'s menu, not the other end\'s')
}

// "Show it on the plan" — first row for the origin, which cannot be made active.
await at(210, nearEndRows[0]); await page.waitForTimeout(1200)
await page.screenshot({ path: join(shotDir, 'field-jumped.png') })
const jumped = (await stationSpots(sketchTop, sketchBottom)).active
if (jumped === null) {
  fail('jumping to a station from the table did not put it on screen')
} else {
  const middle = [Math.round(box.width / 2), Math.round((sketchTop + sketchBottom) / 2)]
  const offBy = Math.hypot(jumped[0] - middle[0], jumped[1] - middle[1])
  if (offBy > 40) {
    fail(`the table's jump left the station ${Math.round(offBy)}px from the middle of the sketch`)
  } else {
    pass('and it can take you to that station on the plan, which is the two halves joined up')
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
await at(...(await drawingMenuRow('symbol'))); await page.waitForTimeout(800)
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

// ---- and a stream is blue even when the brush is not -----------------------------------------
// The app quietly overrides the brush for the one water symbol, because water is drawn blue on
// every published cave survey there has ever been. The brush here is black — nothing in this run
// has changed it — so a stamp that comes out black would mean the rule never fired.
await at(...toolCell(5)); await page.waitForTimeout(600)
await at(...(await drawingMenuRow('symbol'))); await page.waitForTimeout(800)
await at(...PALETTE_WATER); await page.waitForTimeout(700)
await at(250, 300); await page.waitForTimeout(900)

const waterColour = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  const stamped = (JSON.parse(localStorage.getItem(key)).symbols ?? [])
    .find((s) => s['symbol-id'] === 'water-flow')
  return stamped ? stamped.colour : 'not stamped'
})
if (waterColour !== 'BLUE') {
  fail(`the water symbol was stamped ${waterColour}, not BLUE`)
} else {
  pass('a stream is stamped blue whatever colour the brush is set to')
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

// ---- the view can follow the survey ---------------------------------------------------------
// Without this the view re-fits the *whole cave* as the survey grows, so by the fiftieth station
// the working end is a few pixels across and the surveyor is pinching in after every leg.
// `buttonAutoRecentre` keeps the active station in the middle at the zoom they chose. Turned on
// here and checked against the *next* station the readings below make, then turned off again so
// the rest of this file sees the app's own default.
//
// Turned on *before* the settings screen is saved, deliberately. This preference is set from the
// drawing menu and the settings screen does not show it — and the first version of that screen
// built its saved value from the three switches it does show, quietly resetting this one. Doing it
// in this order means that comes back as a failure here rather than as a surveyor wondering why the
// view stopped following them after they adjusted a tolerance.
await toggleOption('auto-recentre')

await at(...OVERFLOW); await page.waitForTimeout(500)
await at(...(await menuRow('surveying', 1))); await page.waitForTimeout(800)
await retype(SETTING_DISTANCE, '0.5')
await retype(SETTING_ANGLE, '12')
// And the preferences on this screen, on the way past: a buzz when a station is made, which is how
// a surveyor with the phone in a pocket learns the leg went in, and the two ways of moving the
// drawing without changing tool. All three are flipped from their defaults, so the file that comes
// out says the screen was actually read rather than that the defaults happened to be written.
await at(...SETTING_BUZZ); await page.waitForTimeout(300)
await at(...SETTING_HOT_CORNERS); await page.waitForTimeout(300)
await at(...SETTING_TWO_FINGER); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-surveying-settings.png') })
await at(...SETTINGS_SAVE); await page.waitForTimeout(700)

const savedPreferences = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('preferences.txt'))
  return key ? localStorage.getItem(key) : null
})
if (savedPreferences === null) {
  fail('the app preferences were not written to storage')
} else if (!savedPreferences.includes('buzzOnNewStation=false')) {
  fail(`turning the buzz off was not saved (${savedPreferences.trim()})`)
} else {
  pass('the new-station buzz can be turned off, and stays off')
}

if (
  !savedPreferences?.includes('hotCorners=false') ||
  !savedPreferences?.includes('twoFingerMove=true')
) {
  fail(`the sketch-movement preferences were not saved (${savedPreferences?.trim()})`)
} else {
  pass('the corners and the two-finger drag can each be turned the other way, and stay')
}

// And turned off, the corners are inert rather than merely invisible — which is the half that
// matters, and the half a pixel check cannot tell you. The same drag that panned the drawing a
// moment ago now marks it.
if (leftCorners.length === 2) {
  const from = cornerCentres(leftCorners, Math.round(cornerSide / 2))[1]
  const before = await planStrokes()
  await drag(from, [from[0] + 120, from[1] - 90]); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-corners-off.png') })
  const after = await planStrokes()
  if (after !== before + 1) {
    fail(
      'with the corners turned off, a drag from one still refused to draw ' +
        `(${JSON.stringify(from)}: ${before} -> ${after})`,
    )
  } else {
    pass('turning the corners off gives the pencil the whole page back')
  }
  // Undone, so the drawing the export checks below read is the one they were written for.
  await at(...toolCell(6)); await page.waitForTimeout(700)
  if ((await planStrokes()) !== before) {
    fail('the stroke drawn to test the corners could not be undone')
  }
}

// Back on, because the rest of this file is written for the app's own defaults.
await at(...OVERFLOW); await page.waitForTimeout(500)
await at(...(await menuRow('surveying', 1))); await page.waitForTimeout(800)
await at(...SETTING_HOT_CORNERS); await page.waitForTimeout(300)
await at(...SETTINGS_SAVE); await page.waitForTimeout(700)

for (const [d, a, i] of sloppy) await reading(d, a, i)
if ((await connectingLegs()) !== beforeSloppy + 1) {
  fail('loosening the tolerances did not let the same readings make a station')
} else {
  pass('loosened tolerances let a compass-and-tape survey make stations')
}

// The station those readings just made should now be in the middle of the sketch, which is what
// "follow the survey" means. Found by its amber brackets — the one thing on the plan drawn in that
// colour — rather than by trusting that the screen changed, which it would have anyway: a new leg
// was drawn.
await page.screenshot({ path: join(shotDir, 'field-auto-recentre.png') })
const activeSpot = (await stationSpots(sketchTop, sketchBottom)).active
if (activeSpot === null) {
  fail('the active station was not on screen at all after a leg went in')
} else {
  const middle = [Math.round(box.width / 2), Math.round((sketchTop + sketchBottom) / 2)]
  const offBy = Math.hypot(activeSpot[0] - middle[0], activeSpot[1] - middle[1])
  if (offBy > 40) {
    fail(
      `following the survey left the active station ${Math.round(offBy)}px from the middle ` +
        `(${JSON.stringify(activeSpot)} against ${JSON.stringify(middle)})`,
    )
  } else {
    pass('the view follows the survey: a new station lands in the middle of the screen')
  }
}

// Off again, so the rest of this file sees the Android app's own default.
await toggleOption('auto-recentre')

// ---- which end of the survey you are working at ----------------------------------------------
// Three of the app's display behaviours, and one question: on a plan that has grown past a
// screenful of red lines, where am I? The leg just taken is magenta, and everything that does not
// hang off the working station can be faded back to a fifth alpha.
const magentaPixels = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // md_magenta, #FF00FF, which the app uses for this and for nothing else. Full strength, so no
    // threshold is needed: an antialiased edge of it is not magenta and does not need to be.
    let count = 0
    for (let i = 0; i < px.length; i += 4) {
      if (px[i] > 240 && px[i + 1] < 40 && px[i + 2] > 240) count++
    }
    return count
  }, [b64])
}

// How much *centreline* is on the screen, which is what the fade acts on. Counting all the ink
// would not do: the fade leaves the sketch alone, and on a page with a passage wall, a label and
// two symbols drawn on it the ink from those swamps a change to a handful of red lines — the first
// attempt at this check measured a half-percent drop and reported that fading did nothing.
const centrelinePixels = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // Red legs (#FF0000) and salmon splays (#FF8080) at full strength. A fifth of either over the
    // pale canvas comes out a pale pink whose green channel is far above this, so a faded leg is
    // not counted — which is the whole measurement.
    let count = 0
    for (let y = 60; y < 780; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] > 200 && px[i + 1] < 150 && px[i + 2] < 150) count++
      }
    }
    return count
  }, [b64])
}

const magentaBefore = await magentaPixels()
if (magentaBefore < 20) {
  fail(`the leg just taken is not marked (${magentaBefore} magenta pixels)`)
} else {
  pass('the leg just taken is drawn in the app\'s magenta, so the working end is findable')
}

await toggleOption('latest-leg')
const magentaOff = await magentaPixels()
if (magentaOff !== 0) {
  fail(`turning the mark off left ${magentaOff} magenta pixels on the plan`)
} else {
  pass('and it can be turned off, for a surveyor who would rather it were not there')
}
await toggleOption('latest-leg')

const litBeforeFade = await centrelinePixels()
await toggleOption('fade')
await page.screenshot({ path: join(shotDir, 'field-faded.png') })
const litFaded = await centrelinePixels()
if (!(litFaded < litBeforeFade * 0.5)) {
  fail(`fading the rest left most of the centreline solid (${litBeforeFade} then ${litFaded})`)
} else {
  pass('everything but the working end can be faded back, without moving the view')
}

// Off again: every check after this reads the plan, and a faded plan would be a different one.
await toggleOption('fade')
const litRestored = await centrelinePixels()
if (!(litRestored > litFaded)) {
  fail(`turning the fade off did not bring the cave back (${litFaded} then ${litRestored})`)
} else {
  pass('and turning it off brings the rest of the cave back')
}

// ---- north is on the plan, and can be taken off it ---------------------------------------
// A plan with no north on it is a picture rather than a survey. The arrow does not swing with the
// phone — there is no magnetometer behind it — but `Projection2D.PLAN` maps the northing to minus
// the screen y, so north on a plan really is up and a fixed arrow is correct rather than
// approximate. The check is that it is drawn at all, and that `buttonShowCompass` reaches it.
//
// The window is the bottom-left corner above the scale bar's own label, measured off a rendered
// frame rather than guessed: the arrow occupies roughly forty pixels by fifty there, and the
// label starts five pixels below it.
//
// The drop is asserted rather than the emptiness, because the arrow is drawn in screen
// coordinates over whatever the plan happens to show, and a passage wall in that corner would
// leave ink behind with the arrow off. A drop of eight thousand cannot be anything but the arrow:
// it is nineteen thousand on its own.
const NORTH_PATCH = [16, box.height - 250, 52, box.height - 200]

const northDrawn = await inkAround(NORTH_PATCH)
await toggleOption('north')
const northHidden = await inkAround(NORTH_PATCH)
if (!(northDrawn - northHidden > 8000 && northHidden < northDrawn / 2)) {
  fail(`the north arrow is not where it should be (${northDrawn} then ${northHidden})`)
} else {
  pass('the plan is drawn with north on it, and north can be taken off it')
}
await toggleOption('north')

// ---- the sketch toggles are remembered ---------------------------------------------------
// Five of the twelve were session-only until the menu was split: a surveyor who turned the splays
// off got them back on the next run, which for a preference is the same as not having it. Every
// one is a persisted `SketchPreferences.Toggle` in the Android app. So: turn the grid off and look
// in storage for it, the same way the tolerances are checked below. What is being tested is that
// the value reached a file, which is the half that was missing and the half no amount of reading
// the code proves.
await toggleOption('grid')

const savedToggles = await page.evaluate(() =>
  localStorage.getItem('sexytopo:f:preferences.txt'),
)
if (!savedToggles || !savedToggles.includes('showGrid=false')) {
  fail(`the sketch toggles were not written to storage (${JSON.stringify(savedToggles)})`)
} else {
  pass('a sketch toggle is remembered, so it is still set the next time the app opens')
}
await toggleOption('grid')


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
await at(...(await menuRow('trip', 1))); await page.waitForTimeout(800)
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
await at(...(await menuRow('export', 1))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-export.png') })

const download = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...(await exportSaveFile())),
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

// ---- and every other format is reachable without a gesture ---------------------------------
// Eight formats do not fit across a phone. They used to sit in a row that scrolled sideways, which
// hid four of them behind a gesture that does not work — a drag beginning on a chip is taken by
// the chip and moves the row about thirty pixels — so the chips now wrap. This exports the Therion
// `.th2`, the last format that used to be off the edge, and the one with the most structure to get
// wrong: it is the drawing rather than the centreline.
await page.screenshot({ path: join(shotDir, 'field-export-formats.png') })
await at(...(await exportChip('th2'))); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-export-th2.png') })

const th2Download = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...(await exportSaveFile())),
]).then(([d]) => d)

if (!th2Download) {
  fail('the .th2 chip produced no download — the format may be off the edge of the screen again')
} else if (th2Download.suggestedFilename() !== 'Swildons.plan.th2') {
  fail(`the .th2 came out named ${th2Download.suggestedFilename()}`)
} else {
  const th2 = readFileSync(await th2Download.path(), 'utf8')
  if (!th2.includes('encoding utf-8')) {
    fail('the .th2 has no encoding line, so Therion will not read it')
  } else if (!th2.includes('scrap Swildons-plan')) {
    fail(`the .th2 has no plan scrap: ${th2.slice(0, 200)}`)
  } else if (!th2.includes('##XTHERION##') || !th2.includes('Swildons.plan.xvi')) {
    fail('the .th2 does not reference the tracing image it is meant to be drawn over')
  } else {
    pass('every export format can be reached on a phone, and the .th2 is right')
  }
}

// ---- and Therion can actually build what comes out -----------------------------------------
// Therion does not compile a .th; it compiles a project, and the project file is the .thconfig.
// Without one, everything this app exports for Therion is a pile of files somebody has to write a
// config for before they can look at any of it — and the .th has to name both scraps, or the
// project builds a centreline with no cave on it.
//
// The two are checked together because that is the only way to check either: what matters is that
// the names in one file are the names the other saves under.
await at(...(await exportChip('thconfig'))); await page.waitForTimeout(700)

const thconfig = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...(await exportSaveFile())),
]).then(([d]) => d)

await at(...(await exportChip('th'))); await page.waitForTimeout(700)

const th = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...(await exportSaveFile())),
]).then(([d]) => d)

if (!thconfig || !th) {
  fail('the .thconfig or the .th produced no download')
} else if (thconfig.suggestedFilename() !== 'Swildons.thconfig') {
  fail(`the project file came out named ${thconfig.suggestedFilename()}`)
} else {
  const config = readFileSync(await thconfig.path(), 'utf8')
  const centreline = readFileSync(await th.path(), 'utf8')
  if (!config.includes('source "Swildons.th"')) {
    fail(`the project file does not name the survey beside it: ${config.slice(0, 120)}`)
  } else if (!config.includes('export map -proj plan') || !config.includes('-proj extended')) {
    fail('the project file does not ask Therion for either drawing')
  } else if (!centreline.includes('input "Swildons.plan.th2"')) {
    fail('the .th does not pull in the plan scrap, so Therion would draw no cave')
  } else if (!centreline.includes('input "Swildons.ee.th2"')) {
    fail('the .th does not pull in the elevation scrap')
  } else {
    pass('the Therion export is a project Therion can build, drawings and all')
  }
}

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
await at(...(await savedSurveyDelete(0, 1))); await page.waitForTimeout(700)
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
await at(...(await savedSurveyDelete(0, 1))); await page.waitForTimeout(700)
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
await at(...(await menuRow('import', 0))); await page.waitForTimeout(800)
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

// ---- and so can somebody else's Survex file ---------------------------------------------------
// The case importing actually exists for: a club's existing survey of the cave, in the format the
// rest of caving uses rather than this app's own. The file is put into the app's storage directly
// rather than through the chooser — the chooser is proved above, and the same storage root is what
// iOS shows in the Files app — and the JSON is taken back out so the dialog has one row again and
// the known row position still points at the file being imported.
await page.evaluate((svx) => {
  localStorage.removeItem('sexytopo:f:Eastwater.data.json')
  localStorage.setItem('sexytopo:f:Bar Pot.svx', svx)
}, EXAMPLE_SURVEX)
await at(...OVERFLOW); await page.waitForTimeout(600)
// One saved survey now: the Eastwater just imported.
await at(...(await menuRow('import', 1))); await page.waitForTimeout(1000)
await page.screenshot({ path: join(shotDir, 'field-import-survex-dialog.png') })
await at(...IMPORT_FIRST_ROW); await page.waitForTimeout(1400)
await page.screenshot({ path: join(shotDir, 'field-import-survex.png') })

const survex = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.includes('Bar Pot/Bar Pot.data.json'))
  return key ? localStorage.getItem(key) : null
})
if (survex === null) {
  fail("a Survex file from another tool did not become a survey in the library")
} else {
  const survey = JSON.parse(survex)
  const stations = survey.stations ?? []
  const legs = stations.flatMap((station) => station.legs ?? [])
  if (legs.length !== 2) {
    fail(`the imported Survex file has ${legs.length} legs, not the 2 it was written with`)
  } else if (stations[0]?.comment !== 'entrance') {
    fail(`the imported Survex station comment came in as "${stations[0]?.comment}"`)
  } else if (Math.abs((legs[0].distance ?? 0) - 8) > 0.005) {
    fail(`the imported Survex leg is ${legs[0].distance} m, not the 8 it was written with`)
  } else {
    pass("a Survex file from other software can be brought in and read")
  }
}

// ---- and PocketTopo's own binary file ----------------------------------------------------------
// The one import that is not text. `localStorage` holds strings, so the chooser stores a `.top` as
// base64 under the store's binary key prefix and `BrowserFileStore.readBytes` decodes it — a path
// nothing else in the app uses, and one that fails silently if it is wrong: a byte lost in a length
// prefix moves everything after it. The file is the Android app's own `CeiledUp.top`.
const topFile = readFileSync(new URL('./fixtures/CeiledUp.top', import.meta.url))
// Only the import candidates, not everything at the root: the settings, the preferences and the
// device log live there too, and clearing those mid-run would quietly undo checks above.
await page.evaluate(() => {
  for (const key of Object.keys(localStorage)) {
    if (/^sexytopo:f:[^/]+\.(json|svx|th|txt)$/i.test(key)) localStorage.removeItem(key)
  }
})
await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...(await menuRow('import', 2))); await page.waitForTimeout(900)

chosenFile = { name: 'CeiledUp.top', mimeType: 'application/octet-stream', buffer: topFile }
const choosersBeforeTop = fileChoosersOpened
await at(...IMPORT_CHOOSE)
await page.waitForTimeout(2500)
if (fileChoosersOpened === choosersBeforeTop) {
  fail('the file chooser never opened for the PocketTopo file')
} else {
  const stored = await page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.startsWith('sexytopo:b:'))
    return key ? localStorage.getItem(key).length : 0
  })
  if (stored === 0) {
    fail('the chosen .top was not stored as binary')
  } else {
    await page.screenshot({ path: join(shotDir, 'field-import-top.png') })
    await at(...IMPORT_FIRST_ROW); await page.waitForTimeout(1600)

    const survey = await page.evaluate(() => {
      const key = Object.keys(localStorage).find((k) => k.includes('CeiledUp/CeiledUp.data.json'))
      return key ? JSON.parse(localStorage.getItem(key)) : null
    })
    const plan = await page.evaluate(() => {
      const key = Object.keys(localStorage).find((k) => k.includes('CeiledUp/CeiledUp.plan.json'))
      return key ? JSON.parse(localStorage.getItem(key)) : null
    })
    if (survey === null) {
      fail('the PocketTopo file did not become a survey in the library')
    } else if ((survey.stations ?? []).length !== 12) {
      fail(`the imported .top has ${(survey.stations ?? []).length} stations, not the 12 it holds`)
    } else if ((plan?.paths ?? []).length !== 162) {
      fail(`the imported .top drew ${(plan?.paths ?? []).length} strokes, not the 162 it holds`)
    } else {
      pass("PocketTopo's own binary file imports, drawing and all")
    }
  }
}

// ---- the cave in three dimensions -------------------------------------------------------------
// Last of all, because it takes the whole screen and has its own gestures. The demo cave rather
// than whichever survey happened to be open: two legs prove the projection runs, a whole cave
// proves it draws a cave.
const savedCount = await page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})
// ---- the app says who wrote it and under what licence ---------------------------------------
// This build carries several thousand lines of somebody else's GPL-3.0 code and had neither their
// names nor the licence anywhere a user could see them. What is *in* the text has its own test —
// the names, the licence, the Latin-1 rule the bundled font imposes. What can only be checked here
// is that the box opens and that Material has not clipped it to nothing: it is a screenful and a
// half of text, and a Compose dialog that does not fit is cut off from the bottom, which is where
// the licence is.
await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...(await menuRow('about', savedCount))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-about.png') })

const aboutHeight = await dialogHeight()
if (aboutHeight === null) {
  fail('the About box did not open, so the licence is nowhere in the app')
} else if (aboutHeight < box.height * 0.4) {
  fail(`the About box is only ${aboutHeight}px tall, which is not the text it should hold`)
} else {
  pass('the app says who wrote it and under what licence')
}
await page.keyboard.press('Escape'); await page.waitForTimeout(600)

await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...(await menuRow('demo', savedCount))); await page.waitForTimeout(900)
await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...(await menuRow('3d', savedCount))); await page.waitForTimeout(1400)
await page.screenshot({ path: join(shotDir, 'field-3d.png') })

// The legs are drawn in the renderer's own red, which nothing else on this screen uses.
const legPixels = async () => {
  const clip = { x: box.x, y: box.y + 60, width: 420, height: 700 }
  const b64 = (await page.screenshot({ clip })).toString('base64')
  return page.evaluate(async (data) => {
    const img = new Image()
    await new Promise((r) => {
      img.onload = r
      img.src = 'data:image/png;base64,' + data
    })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let red = 0
    for (let i = 0; i < px.length; i += 4) {
      if (px[i] > 120 && px[i + 1] < 110 && px[i + 2] < 110) red++
    }
    return red
  }, b64)
}

const drawnIn3D = await legPixels()
if (drawnIn3D < 100) {
  fail(`the 3D view drew almost nothing (${drawnIn3D} leg pixels)`)
} else {
  pass(`the survey is drawn in three dimensions (${drawnIn3D} leg pixels)`)
}

const beforeTurning = await page.screenshot({ clip: { x: box.x, y: box.y + 60, width: 420, height: 700 } })
await drag([120, 400], [320, 300]); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-3d-turned.png') })
const afterTurning = await page.screenshot({ clip: { x: box.x, y: box.y + 60, width: 420, height: 700 } })
if (Buffer.compare(beforeTurning, afterTurning) === 0) {
  fail('dragging did not turn the cave')
} else if ((await legPixels()) < 100) {
  fail('turning the cave lost it')
} else {
  pass('one finger turns the cave, and it is still there afterwards')
}

await at(...THREE_D_CLOSE); await page.waitForTimeout(900)
const backToTheSketch = await page.evaluate(() => document.querySelectorAll('canvas').length)
if (backToTheSketch === 0) {
  fail('closing the 3D view left no canvas at all')
} else {
  pass('the 3D view closes back to the survey')
}

// ---- and all of it has to fit on a smaller phone ---------------------------------------------
// Everything above ran on a 420x900 screen. An iPhone SE is 375x667, and this app has grown things
// that do not obviously fit one: a drawing menu that went from thirteen rows to sixteen is 768
// pixels of menu on 667 pixels of phone, and the station dialog - a name, a comment, four passage
// measurements and the elevation direction - is most of a screen before a keyboard takes a third
// of what is left. Material 3 scrolls a dropdown that does not fit and *clips* a dialog that does
// not, which is why the two tallest dialogs here were made scrollable.
//
// What this checks is the floor: at that size the toolbar is still where it is computed to be and
// the canvas still takes a stroke. The screenshot beside it is for a human to look at, because
// "does this look usable on a small phone" is not a thing a pixel count answers.
await page.setViewportSize({ width: 375, height: 667 })
await page.waitForTimeout(1000)
box = await (await page.$('canvas')).boundingBox()
const small = box
const tapSmall = (x, y) => page.mouse.click(small.x + x, small.y + y)
const smallToolRow = small.height - 20
const smallColumn = small.width / 9

await page.screenshot({ path: join(shotDir, 'field-small-screen.png') })

// Ink over the middle of the sketch, whatever survey happens to be open by now.
const middleInk = async () => {
  const b64 = (await page.screenshot({ clip: small })).toString('base64')
  return page.evaluate(async ([data]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    let ink = 0
    for (let y = 220; y < 320; y++) {
      for (let x = 60; x < 320; x++) {
        const i = (y * c.width + x) * 4
        const lightest = Math.max(px[i], px[i + 1], px[i + 2])
        if (lightest < 200) ink += 200 - lightest
      }
    }
    return ink
  }, [b64])
}

await tapSmall(smallColumn * 1.5, smallToolRow); await page.waitForTimeout(500)
const smallInkBefore = await middleInk()
await page.mouse.move(small.x + 80, small.y + 250)
await page.mouse.down()
await page.mouse.move(small.x + 300, small.y + 290, { steps: 12 })
await page.mouse.up()
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-small-screen-drawn.png') })
if (!((await middleInk()) > smallInkBefore)) {
  fail('on a 375x667 screen the toolbar or the canvas was not where it should be — no stroke')
} else {
  pass('on an iPhone SE-sized screen the toolbar still works and the sketch still takes a stroke')
}

// ---- the drawing can have the app bar's share of the screen ---------------------------------
// `action_fullscreen`. It matters most exactly here, on the smallest screen: the app's own chrome
// is a quarter of a portrait phone and about half of a landscape one, and the app bar is the piece
// a surveyor mid-stroke has no use for.
//
// What has to be true is both halves. The sketch gets taller — measured by where the toolbar's
// green starts, which is the bottom of everything above it — and there is still a way back, since
// hiding the app bar hides the only route to the menu that turned it on.
const appBarGreen = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, panel]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // How many rows at the very top are mostly the panel green: the app bar's height, or the
    // handle's when the app bar is gone.
    let rows = 0
    for (let y = 0; y < c.height; y++) {
      let green = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] === panel[0] && px[i + 1] === panel[1] && px[i + 2] === panel[2]) green++
      }
      if (green < c.width * 0.5) break
      rows++
    }
    return rows
  }, [b64, SKETCH_PANEL])
}

const chromeBefore = await appBarGreen()
await at(small.width - 16, 26); await page.waitForTimeout(700)
const fullScreenSaved = await page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})
await at(...(await menuRow('fullscreen', fullScreenSaved))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-full-screen.png') })

const chromeAfter = await appBarGreen()
if (!(chromeAfter < chromeBefore / 2)) {
  fail(`full screen left ${chromeAfter} pixels of app bar where there were ${chromeBefore}`)
} else {
  pass(`full screen gives the drawing the app bar back (${chromeBefore} to ${chromeAfter} pixels)`)
}

// And out again, which is the half that matters: the app bar is the only way to the menu that
// turned this on, so the handle left in its place has to work.
await at(Math.round(small.width / 2), Math.round(chromeAfter / 2)); await page.waitForTimeout(900)
if ((await appBarGreen()) < chromeBefore) {
  fail('the handle left in place of the app bar did not bring it back — the menu is unreachable')
} else {
  pass('and the handle brings it back, so nobody is stranded in it')
}

// ---- and a dialog too tall for that screen scrolls rather than being cut off -----------------
// The claim this replaces was "reasoned, not run". Material sizes a dialog to fit the window and
// *clips* what does not fit — from the bottom, which is where the buttons are — so the three
// dialogs with several fields in them were given `verticalScroll` by reading the layout, with
// nothing exercising it.
//
// The About box is the instrument for testing that, because it is a screenful and a half of text
// and so overflows an iPhone SE whatever else is going on. What is checked is the mechanism: the
// card fits inside the window, its content scrolls, and its button is on screen and works.
//
// What is still not checked is the keyboard, which takes a third of this screen and which a
// headless browser has not got. So this proves a dialog taller than the window behaves; it does
// not prove the station dialog behaves *with a keyboard up*. That still needs a phone.
await at(small.width - 16, 26); await page.waitForTimeout(700)
const smallSaved = await page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})
await at(...(await menuRow('about', smallSaved))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-small-screen-dialog.png') })

const smallDialogTop = await dialogTop()
const smallDialogHeight = await dialogHeight()
if (smallDialogTop === null || smallDialogHeight === null) {
  fail('the About box did not open on a 375x667 screen')
} else if (smallDialogTop + smallDialogHeight > small.height) {
  fail(
    `the dialog runs from ${smallDialogTop} to ${smallDialogTop + smallDialogHeight} on a ` +
      `${small.height}-pixel screen, so its buttons are off the bottom`,
  )
} else {
  pass('a dialog too tall for an iPhone SE is sized to the screen rather than run off it')

  // Its content scrolls. A wheel over the middle of the card, then the same window of pixels
  // again: if `verticalScroll` were not there the text would not have moved.
  const textWindow = [
    40,
    smallDialogTop + 60,
    small.width - 40,
    smallDialogTop + smallDialogHeight - 60,
  ]
  const beforeScroll = await inkAround(textWindow)
  await page.mouse.move(small.x + small.width / 2, small.y + smallDialogTop + 120)
  await page.mouse.wheel(0, 400)
  await page.waitForTimeout(700)
  const afterScroll = await inkAround(textWindow)

  if (beforeScroll === afterScroll) {
    fail('the dialog did not scroll, so everything below the fold is unreachable')
  } else {
    pass('and it scrolls, so what is below the fold can be read')
  }

  // And the button at the bottom of it is a button, not a picture of one.
  const confirm = await dialogConfirm()
  if (confirm === null) {
    fail('the dialog has no button on screen to close it with')
  } else {
    await at(...confirm); await page.waitForTimeout(800)
    if ((await dialogTop()) !== null) {
      fail('tapping the dialog\'s own button did not close it on a small screen')
    } else {
      pass('and the button below the text can still be reached and pressed')
    }
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
