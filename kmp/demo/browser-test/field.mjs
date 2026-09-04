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
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:8080/index.html'
const shotDir = process.argv[3] ?? 'field-screenshots'
mkdirSync(shotDir, { recursive: true })

// A real file in the app's own format, small enough to read: one leg, two stations.
// The drawing that goes with it. A SexyTopo survey is four files, and this is the one that holds
// the trip's actual work: `Name.data.json` is a minute a station, `Name.plan.json` is the hours.
// Written in the app's own sketch format, one black path of four points.
const EXAMPLE_PLAN_JSON = JSON.stringify({
  name: 'Eastwater',
  paths: [
    {
      colour: 'BLACK',
      points: [
        { x: 0.0, y: 0.0 },
        { x: 1.5, y: 0.5 },
        { x: 2.0, y: 2.5 },
        { x: 3.5, y: 3.0 },
      ],
    },
  ],
  labels: [],
  symbols: [],
  'x-sections': [],
  settings: { 'cross-section-scale': 1.0 },
})

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

/**
 * Where a control is, asked of the app rather than worked out from the picture of it.
 *
 * Compose paints the whole app into one canvas, which is why much of this file is arithmetic on
 * pixels. It also builds an accessibility tree of real DOM nodes laid over that canvas —
 * `Modifier.testTag` becomes an element's id, `contentDescription` its aria-label. So a menu row
 * or a dialog row can be asked for by name, and one that moves stops being a silent mis-tap
 * several hundred lines later.
 *
 * Only the popup that is open now, though: `rememberChrome` says why, and what is done about the
 * screen behind it.
 *
 * Returns canvas coordinates rather than clicking the element, so the caller still goes through
 * `at` and a real mouse: the point of this file is that the app works under a finger, and a
 * synthetic DOM click would prove less than the tap it replaces.
 */
const nodeAt = async (selector) => {
  const handle = await page.$(selector)
  if (handle === null) return null
  const rect = await handle.boundingBox()
  if (rect === null) return null
  return [
    Math.round(rect.x - box.x + rect.width / 2),
    Math.round(rect.y - box.y + rect.height / 2),
  ]
}

/** The same, but a missing one is a failure with the app's own list of what it does offer. */
const nodeFor = async (selector) => {
  const where = await nodeAt(selector)
  if (where !== null) return where
  const offered = await namedNodes()
  throw new Error(
    `nothing on screen answers to ${selector}. ` +
      `The app offers: ${offered.slice(0, 40).join(', ') || '(nothing)'}`)
}

/**
 * Everything the app has named, as the accessibility tree lays it out.
 *
 * Asked through Playwright's own selectors rather than `document.querySelectorAll`, because
 * `ComposeViewport` puts the canvas, the hidden text input and this tree inside an open shadow
 * root: the page's own DOM query stops at that boundary and reports an app with nothing in it.
 */
const namedNodes = async () => {
  const handles = await page.$$('[id], [aria-label]')
  const rows = []
  for (const handle of handles) {
    rows.push(
      await handle.evaluate((e) => {
        const bits = []
        if (e.id) bits.push(`#${e.id}`)
        const label = e.getAttribute('aria-label')
        if (label) bits.push(`"${label}"`)
        const text = (e.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 24)
        if (text) bits.push(text)
        return bits.join(' ')
      }),
    )
  }
  return rows.filter(Boolean)
}

/**
 * Where the app's own furniture is, taken down while the app can still be asked.
 *
 * Compose 1.12.0's web listener holds one semantics owner at a time, and every popup — a menu, a
 * dialog — attaches its own. So opening one replaces the tree with that popup's contents, and
 * closing it leaves the tree frozen on them: from the first menu onwards, the app *behind* the
 * popup has no names at all. What the tree does hold, reliably, is whichever popup is open now,
 * because opening it is what makes Compose sync again — which is where rows drift, and where the
 * rest of this file asks for things by name.
 *
 * The app bar and the toolbar do not move once the app has drawn, so they are read once, before
 * anything opens, and their positions kept. That is still the app saying where it put them rather
 * than this file guessing, and it is checked at the moment it is taken: a control that has lost
 * its name fails here, at the top, rather than as a mis-tap four hundred lines down.
 */
const CHROME = new Map()
const rememberChrome = async (...selectors) => {
  for (const selector of selectors) CHROME.set(selector, await nodeFor(selector))
}
const chrome = (selector) => {
  const where = CHROME.get(selector)
  if (where === undefined) throw new Error(`${selector} was never taken down before a menu opened`)
  return where
}

/**
 * Anything the app has named for a screen reader, by the `strings.xml` entry the name comes from.
 *
 * Every button on the sketch toolbar has a `contentDescription` — they are pictures, so they have
 * to — and Compose puts those on the accessibility node as `aria-label`. So the toolbar needs no
 * test tags of its own: it is already named, for a better reason than testing.
 */
const labelled = (resource) => {
  const label = ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`no string called ${resource}`)
  return `[aria-label="${label}"]`
}

/** A menu row by the label it is drawn with, which is what `tagFor` in App.kt makes its id from. */
const tagFor = (label) =>
  'menu-' +
  [...label.toLowerCase()]
    .map((c) => (/[a-z0-9]/.test(c) ? c : '-'))
    .join('')
    .split('-')
    .filter(Boolean)
    .join('-')

/**
 * The Android app's own `strings.xml`, which is where the menu labels come from.
 *
 * Read here for the same reason `Strings.kt` mirrors it and `AndroidStringsTest` checks it: the
 * ids these checks ask for are made from the labels, so taking the labels from the file the app
 * takes them from means a rename upstream moves the test with it instead of breaking it.
 */
const ANDROID_STRINGS = (() => {
  const xml = readFileSync('../../../app/src/main/res/values/strings.xml', 'utf8')
  const strings = {}
  for (const [, name, value] of xml.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    strings[name] = value
      .replace(/<[^>]+>/g, '')
      .replace(/\\'/g, "'")
      .replace(/\\"/g, '"')
      .replace(/&#176;/g, '\u00b0')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .trim()
  }
  return strings
})()

/**
 * Every row this file reaches for, under the resource name `strings.xml` gives it.
 *
 * `demo` is the one with no resource: a generated cave to look at before a real one exists is
 * this port's own, and the Android app has nothing to name it.
 */
const MENU_RESOURCES = {
  file: 'action_file',
  view: 'action_view',
  instrument: 'action_device',
  input: 'action_input',
  tools: 'action_tools',
  settings: 'action_settings',
  help: 'action_help',
  connection: 'action_connection',
  back: null,

  new: 'action_file_new',
  open: 'action_file_open',
  save: 'action_file_save',
  'save-as': 'action_file_save_as',
  delete: 'action_file_delete',
  import: 'action_file_import',
  'import-file': 'action_file_import_file',
  export: 'action_file_export',
  share: 'action_file_share',

  trip: 'action_trip',
  table: 'action_table',
  plan: 'action_plan',
  elevation: 'action_elevation',
  '3d': 'action_3d',
  stats: 'action_stats',
  fullscreen: 'action_fullscreen',
  demo: null,

  connect: 'action_device_connect',
  calibrate: 'device_distox_command_calibration',

  forward: 'action_input_mode_forward',
  backward: 'action_input_mode_backward',
  combined: 'action_input_mode_combo',
  splays: 'action_input_mode_cal_check',

  'undo-last-leg': 'action_undo_last_leg',
  find: 'action_find_station',
  'add-leg': 'action_add_leg',
  'add-splay': 'action_add_splay',
  log: 'action_system_log',

  system: 'action_settings_system',
  survey: 'action_settings_survey',
  general: 'settings_general_title',
  sketching: 'settings_sketching_title',
  'manual-entry': 'settings_manual_data_entry_title',
  instruments: 'settings_instruments_title',

  manual: 'action_guide',
  about: 'action_about',
}
/** The two rows with no `strings.xml` entry to take a label from. */
const MENU_LABELS = { demo: 'Demo cave', back: '\u2039 Back' }

/** The id of a named menu row, whichever page of the menu it is on. */
const menuSelector = (name) => {
  const resource = MENU_RESOURCES[name]
  const label = resource === null || resource === undefined
    ? MENU_LABELS[name]
    : ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`no label for menu row ${name} (${resource})`)
  return `#${tagFor(label)}`
}

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

/** The three dots at the end of the app bar, which is the way into every menu. */
const overflowButton = () => chrome('#overflow')
const NAME_FIELD = [210, 442]
const NAME_CONFIRM = [312, 518]
const ADD_READING = [74, 790]
// Same place as "Add reading", because it is the button that becomes it.
const START_SURVEYING = [83, 790]
// The reading dialog, as offsets rather than screen positions.
//
// These were nine absolute coordinates, and adding the fourth input mode broke five of them at
// once: four chips wrap onto two rows, the card grew fifty pixels taller, and a centred card that
// grows moves *both* its edges — so every field shifted up and every button shifted down while the
// numbers stayed still. `FIELD_DISTANCE` landed on the bottom edge of its box.
//
// Everything above the chips is a fixed distance below the card's top edge; the buttons are a
// fixed distance above its bottom edge. Both survive the card changing height, which is what a
// dialog does whenever anything is added to it.
const CARD_DISTANCE = [144, 107]
const CARD_AZIMUTH = [284, 107]
const CARD_INCLINATION = [144, 181]
const CARD_SIGN_TOGGLE = [255, 177]
// The same card with `pref_deg_mins_secs` on: the two angles become three narrow boxes each, and
// the +/- button wraps to a line of its own. Measured off a rendered frame, from the card's own
// top like everything else here, because the card is taller in this mode and vertically centred.
const DMS_AZIMUTH_DEGREES = [111, 201]
const DMS_AZIMUTH_MINUTES = [191, 201]
const DMS_INCLINATION_DEGREES = [111, 295]
const DMS_INCLINATION_MINUTES = [191, 295]
const DMS_SIGN_BUTTON = [107, 354]
// Two chips a row, in `OFFERED_MODES` order: Forward, Backsight, then Fore + back, Splays only.
const CARD_MODES = [[116, 243], [214, 243], [128, 295], [242, 295]]
// The four passage-size boxes, which are on the card only while `pref_lrud_fields` is on — so
// these offsets are only ever used by the check that turns it on. Measured off a rendered frame,
// like every other offset here.
const CARD_LRUD = [[105, 450], [175, 450], [244, 450], [314, 450]]
const CARD_BUTTONS_ABOVE_BOTTOM = 44
const CARD_CANCEL_X = 139
const CARD_ADD_SPLAY_X = 225
const CARD_ADD_LEG_X = 309
// `SexyTopoColours.panelBackground`, the green the sketch toolbar is drawn on and nothing else at
// the bottom of the screen is.
const SKETCH_PANEL = [127, 175, 127]
const TABLE_TAB = [281, 26]
const PLAN_TAB = [325, 26]
const ELEVATION_TAB = [369, 26]
/**
 * The nth row of the table, in the middle of its *Dist* column.
 *
 * The table is a screen, not a popup, so the accessibility tree has stopped describing it by the
 * time this file gets here — the way to it is through the overflow menu, and opening that is what
 * takes the tree away. It is measured instead, but every number in it is read off the screen: the
 * left margin is one of two alternating greys, so the first place below the green column header
 * where that grey appears is the top of the first row, and the next place it changes is the top of
 * the second. Which gives the row height and where the rows start without knowing how tall the app
 * bar is, how tall the header is, or how tall a row is — the three guesses that `66 + 26 * n` was,
 * all of which moved when the table gained that header.
 *
 * A row is only there if something is drawn in it, and asking that is what turns "the check tapped
 * empty paper and the assertion after it failed" into a sentence saying the row is not there.
 */
const TABLE_STRIPES = [[255, 255, 255], [245, 245, 245]]
const tableRow = async (n) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  const row = await page.evaluate(async ([data, stripes, wanted]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const colourAt = (x, y) => {
      const i = (y * c.width + x) * 4
      return [px[i], px[i + 1], px[i + 2]]
    }
    const isStripe = (colour) =>
      stripes.some((stripe) => stripe.every((v, k) => Math.abs(colour[k] - v) < 3))
    // The left margin, which is one stripe or the other for every row and neither above them.
    const stripeIndex = (y) => {
      const colour = colourAt(4, y)
      return stripes.findIndex((stripe) => stripe.every((v, k) => Math.abs(colour[k] - v) < 3))
    }
    let top = -1
    for (let y = 0; y < c.height && top < 0; y++) if (stripeIndex(y) >= 0) top = y
    if (top < 0) return null
    let height = -1
    for (let y = top + 1; y < c.height && height < 0; y++) {
      if (stripeIndex(y) !== stripeIndex(top)) height = y - top
    }
    if (height < 0) return null
    // Nothing but the two stripes means an empty page below the last row rather than a row.
    let drawn = false
    for (let y = top + wanted * height; y < top + (wanted + 1) * height && y < c.height; y++) {
      for (let x = 0; x < c.width && !drawn; x++) if (!isStripe(colourAt(x, y))) drawn = true
    }
    return { y: Math.round(top + (wanted + 0.5) * height), drawn }
  }, [b64, TABLE_STRIPES, n])
  if (row === null) throw new Error('the table is not on screen, or has no rows at all')
  if (!row.drawn) throw new Error(`wanted table row ${n} but nothing is drawn in it`)
  return [210, row.y]
}
// The leg menu's buttons are found rather than counted from the top of the screen. The dialog is
// centred, its height depends on how many actions the row can take, and the actions a row can take
// depend on the survey — a leg with splays hanging off its far end cannot be made back into one.
// A fixed y for "Edit reading" was right for a three-button dialog and landed on "Splay comment"
// the day a fourth was added, which is exactly the sort of drift a screenshot does not show.
const ACT_X = 300
// *Add a leg*'s fields, measured down from the top of its own card rather than from the top of the
// screen. The dialog is vertically centred, so its rows all move together when its height changes —
// which it does when `pref_lrud_fields` puts four more boxes in it. Anchoring to the card means one
// number to re-measure instead of six, which is the lesson SETTINGS_DIALOG_HEIGHT above records.
/**
 * *Add a leg*'s fields, by their place in the dialog rather than by an offset into it.
 *
 * `leg_edit_dialog_unified.xml`'s own order, which `AddLegDialog` follows: the station the leg
 * hangs off, then the reading, then the far station and its comment, then the leg's own comment.
 * `editFromStation` arriving above the reading moved every row in this dialog down; asking
 * `numberFieldRows` where the boxes are means that cost no numbers here, and will not cost any
 * next time either. Only the x's are fixed, because two of the boxes share a row.
 */
const ADD_LEG_FIELDS = {
  from: [210, 0],
  distance: [144, 1],
  azimuth: [284, 1],
  inclination: [144, 2],
  to: [210, 3],
  note: [210, 4],
}
const addLegRow = async (which) => {
  if (which === 'add') return dialogConfirm()
  const [x, index] = ADD_LEG_FIELDS[which]
  return [x, (await numberField(index))[1]]
}
const COMMENT_FIELD_ABOVE_SAVE = 76
const COMMENT_SAVE_X = 317
const CONFIRM_DELETE = [292, 496]
/**
 * The overflow menu, by name.
 *
 * `action_bar.xml`'s own submenus, which this port went back to when the flat list grew past the
 * height of an iPhone SE. Which page a row is on is still written down here, because opening a
 * page is a tap that has to happen; *where the row is drawn* is not, because the app says.
 *
 * That is the whole difference between this and what it replaces. The old version counted rows
 * and divided the menu's height by them, so adding a row to a page moved every row below it and
 * the check that meant to tap *New Survey* tapped *Open Survey…* — which is not a failure
 * anywhere near the menu, but a survey that never got named and a hundred later checks reporting
 * that the sketch was never saved.
 */
const MENU_PARENT = {
  new: 'file',
  open: 'file',
  save: 'file',
  'save-as': 'file',
  delete: 'file',
  import: 'file',
  'import-file': 'import',
  export: 'file',
  share: 'file',

  trip: 'view',
  table: 'view',
  plan: 'view',
  elevation: 'view',
  '3d': 'view',
  stats: 'view',
  fullscreen: 'view',
  demo: 'view',

  connect: 'instrument',
  calibrate: 'instrument',

  forward: 'input',
  backward: 'input',
  combined: 'input',
  splays: 'input',

  'undo-last-leg': 'tools',
  find: 'tools',
  'add-leg': 'tools',
  'add-splay': 'tools',
  log: 'tools',

  system: 'settings',
  survey: 'settings',
  general: 'system',
  sketching: 'system',
  'manual-entry': 'system',
  instruments: 'system',

  manual: 'help',
  about: 'help',
}

/** The pages that have to be opened, outermost first, to get at a named row. */
const menuChain = (name) => {
  const chain = []
  for (let group = MENU_PARENT[name]; group !== undefined; group = MENU_PARENT[group]) {
    chain.unshift(group)
  }
  return chain
}

/**
 * Tap a named overflow-menu item, opening its group first if it is on one.
 *
 * The menu must already be open. Returns the coordinates of the final tap rather than performing
 * it, so the call sites read as they did when this was arithmetic.
 */
async function menuRow(name) {
  for (const group of menuChain(name)) {
    await at(...(await nodeFor(menuSelector(group))))
    await page.waitForTimeout(400)
  }
  return nodeFor(menuSelector(name))
}

/**
 * A saved survey on the *Open* or *Delete Survey…* page, by its own name.
 *
 * The library used to be counted into the File page, so every row below it moved by one per
 * survey. It is two pages of its own now — `action_file_open` and `action_file_delete` are
 * submenus in `action_bar.xml` — and a survey is a row named after itself, which is a better
 * handle than its position ever was.
 */
async function savedSurveyRow(group, name) {
  await at(...(await menuRow(group)))
  await page.waitForTimeout(400)
  return nodeFor(`#${tagFor(name)}`)
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
// 900 and not 830: adding *Chase a lost instrument* pushed the dialog past the height of this
// window, so it is now capped at the screen and scrolls — which means its top is the top of the
// screen, and the offsets below are measured from there. The offsets themselves did not move,
// because nothing was added above them; that is the whole point of anchoring to the dialog.
/**
 * Every number field in the open dialog, top to bottom, found rather than measured.
 *
 * `preferences_main.xml` splits the app's settings across five screens, so the old single
 * *Surveying* dialog is now three of them and no offset measured against it survived. Rather than
 * re-measure three dialogs — and again the next time a row is added to any of them — this finds
 * the fields the way `switchRows` finds the switches.
 *
 * How: an M3 `OutlinedTextField` with a value in it floats its label up onto its own top border,
 * which leaves a gap in that line; its **bottom** border is unbroken and spans the card. So the
 * bottom borders are the rows where nearly all of the card's width is not the card's colour. A
 * `HorizontalDivider` looks the same from here, which is why the fields are taken as the longest
 * run of those rows evenly spaced — a divider is on its own, and eight settings in a column are
 * not.
 */
const numberFieldRows = async () => {
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
    // The card's own horizontal extent, so "nearly all of its width" means something.
    let left = c.width
    let right = -1
    for (let y = 0; y < c.height; y++) {
      let first = -1
      let last = -1
      for (let x = 0; x < c.width; x++) {
        if (!isCard(x, y)) continue
        if (first < 0) first = x
        last = x
      }
      if (last - first > 200) {
        if (first < left) left = first
        if (last > right) right = last
      }
    }
    if (right < 0) return []
    // A narrow window just inside the card's left edge rather than the whole of it. Every box on
    // every one of these screens starts there, but they do not all *end* in the same place: the
    // inclination box is half-width, with the +/- button beside it, so a full-width test misses
    // its border and the run of evenly spaced rows breaks in the middle of the dialog.
    const from = left + 26
    const to = Math.min(left + 116, right)
    const span = to - from
    const borders = []
    for (let y = 0; y < c.height; y++) {
      let notCard = 0
      for (let x = from; x <= to; x++) if (!isCard(x, y)) notCard++
      if (notCard > span * 0.95) borders.push(y)
    }
    // The longest evenly spaced run of them, which is the block of fields.
    let best = []
    for (let i = 0; i < borders.length; i++) {
      for (let j = i + 1; j < borders.length; j++) {
        const step = borders[j] - borders[i]
        if (step < 40 || step > 120) continue
        const run = [borders[i], borders[j]]
        let next = borders[j] + step
        while (borders.some((b) => Math.abs(b - next) <= 2)) {
          run.push(borders.find((b) => Math.abs(b - next) <= 2))
          next = run[run.length - 1] + step
        }
        if (run.length > best.length) best = run
      }
    }
    // A field's own middle is half its height above the border that closes it, and an M3 text
    // field is fifty-six of them tall whatever is spaced around it.
    return best.map((b) => b - 27)
  }, [b64, DIALOG_CARD])
}

/**
 * The nth number field of the open dialog. **Negative counts from the end**, as with the switches.
 *
 * From the end where a dialog has to be scrolled to reach the field at all: what the run of rows
 * begins with depends on whether a divider above the block happens to be one field-height clear of
 * it, and what it ends with does not.
 */
const numberField = async (index) => {
  const rows = await numberFieldRows()
  const nth = index < 0 ? rows.length + index : index
  if (nth < 0 || nth >= rows.length) {
    throw new Error(`wanted number field ${index} but the dialog shows ${rows.length}: ${rows}`)
  }
  return [210, rows[nth]]
}

/**
 * A settings row, by the title it is drawn with.
 *
 * `Toggle` names itself after its title, so this finds the row whatever state the switch is in —
 * including greyed out, which the switch-shaped-run search cannot see at all. The tap goes to the
 * right-hand end, where the switch is: the row is the full width of the card and its middle is the
 * wording.
 */
const settingRow = async (resource) => {
  const label = ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`strings.xml has no ${resource}`)
  const handle = await page.$(`#${tagFor(label)}`)
  if (handle === null) throw new Error(`no settings row called ${label}`)
  const rect = await handle.boundingBox()
  if (rect === null) throw new Error(`the settings row ${label} is not drawn`)
  return [
    Math.round(rect.x - box.x + rect.width - 20),
    Math.round(rect.y - box.y + rect.height / 2),
  ]
}

/** A theme chip in the *General* dialog: `pref_theme`, which is a three-value list. */
const themeChip = (name) => nodeFor(`#theme-${name}`)
/**
 * Every switch in the open dialog, top to bottom, found rather than measured.
 *
 * This replaces three hand-measured offsets, and it replaces them because measuring cost a
 * re-measure every single time a row was added to this dialog — three times, and the third was a
 * check that silently stopped doing its job (it turned a setting on and then missed the switch
 * turning it off again, and passed anyway because it only asserted the turning on).
 *
 * How it works: a Material 3 `Switch` is the only thing in the dialog's right-hand column that is
 * a *tall* solid run of non-background colour. Its track is about thirty pixels high; a divider is
 * one pixel; the border of a text field is one pixel at each edge and its interior is the dialog's
 * own surface. So sampling one column and keeping runs between twenty and forty pixels finds the
 * switches and nothing else — whatever order they are in and however many rows are above them.
 */
const switchRows = async () => {
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
    const rgb = (px_, xx, yy) => {
      const i = (yy * c.width + xx) * 4
      return [px_[i], px_[i + 1], px_[i + 2]]
    }
    // The dialog's own surface, sampled where no control is ever laid out: the right-hand column,
    // just under the title.
    const surface = rgb(px, x, Math.min(60, c.height - 1))
    // A *large* margin, because the thing that has to be caught is not the track. An M3 switch
    // that is off has a track within a few units of the dialog's own surface — two thresholds
    // were tried against it and both found only the switched-on ones — but its **thumb** is a
    // solid mid-grey circle and its outline is darker still. Those are what this looks for, and
    // they are present whichever way the switch is set.
    const differs = (p) =>
      Math.abs(p[0] - surface[0]) > 25 ||
      Math.abs(p[1] - surface[1]) > 25 ||
      Math.abs(p[2] - surface[2]) > 25

    // A *band* rather than a single column, counting how much of the switch's width is filled.
    //
    // One column was not enough and the way it failed is worth keeping: an M3 switch that is
    // **off** has a pale grey track that a per-pixel threshold reads as the dialog's own surface,
    // so only the switched-*on* ones were found — and "the last switch" silently became a
    // different setting from the one the check meant. It turned the wrong preference off and the
    // failure surfaced four hundred lines later as a dialog that would not open.
    //
    // A whole track is about fifty pixels wide, so most of the band differs on a switch row and
    // only a few glyphs' worth does on a line of text. That holds for on and off alike.
    const left = x - 26
    const right = x + 26
    const rows = []
    let start = -1
    for (let y = 0; y < c.height; y++) {
      let filled = 0
      for (let xx = left; xx <= right; xx++) if (differs(rgb(px, xx, y))) filled++
      // Ten, not thirty: an on switch fills the whole band with primary, an off one contributes
      // only its thumb and outline. A line of the description text contributes nothing at all,
      // because that column wraps well to the left of the switches.
      const isControl = filled > 10
      if (isControl) {
        if (start < 0) start = y
      } else if (start >= 0) {
        const height = y - start
        // Twelve, not twenty. An on switch fills its whole thirty-pixel track; an off one is
        // found by its thumb alone, which is about sixteen pixels tall — so a minimum of twenty
        // silently kept only the switches that were already on, which is how "the last switch"
        // became a different setting from the one the check meant.
        if (height >= 12 && height <= 40) rows.push(Math.round(start + height / 2))
        start = -1
      }
    }
    return rows
  }, [b64, 320])
}

/**
 * The nth switch in the dialog. **Negative counts from the end**, which is what these checks use.
 *
 * From the end because they wheel to the bottom first and because new settings get added *above*
 * the ones already there — so counting down from the top would drift for the same reason the
 * pixel offsets did, while counting up from the last switch does not.
 */
const settingsSwitch = async (index) => {
  const rows = await switchRows()
  const nth = index < 0 ? rows.length + index : index
  if (nth < 0 || nth >= rows.length) {
    throw new Error(`wanted switch ${index} but the dialog shows ${rows.length}: ${rows}`)
  }
  return [320, rows[nth]]
}

// *Instruments*'s last two switches, counted from that end: everything else on that screen is a
// number field or the amalgamation chips, and both of these sit below all of it.
const SWITCH_LOG_EVERY_FRAME = -1
const SWITCH_CHASE_LOST_INSTRUMENT = -2

// *Sketching*'s five, counted from the top and in `preferences_sketching.xml`'s own order. From
// the top because that screen's eight number fields come after them, so the end of the dialog is
// nowhere near these.
const SWITCH_HOT_CORNERS = 0
const SWITCH_DELETE_FRAGMENTS = 1
const SWITCH_HIGHLIGHT_LATEST_LEG = 2
const SWITCH_TWO_FINGER = 3
const SWITCH_LEGACY_CROSS_SECTIONS = 4

// *Manual entry*'s five switches, counted from the **top** and in the order the dialog lays them
// out. From the top because that dialog opens at the top and its rows are added at the bottom;
// from the end in *Surveying* because that one's rows are added above the reconnect pair.
//
// The five used to be the tail of *Surveying*, and the reason they are not any more is worth
// keeping: that dialog reached eleven settings, and eleven do not fit on a 420-by-900 screen at
// once. `switchRows()` finds switches by scanning the pixels that are actually drawn, so "the
// last switch but five" silently became a switch that was off the bottom of the card — the check
// reported *"wanted switch -6 but the dialog shows 5"*, which is the good version of that failure
// and only happened because the finder counts what it can see rather than what it remembers.
const SWITCH_MANUAL_ENTRY = 0
const SWITCH_BOOK_PASSAGE_SIZE = 1
const SWITCH_WALLS_SQUARE_TO_NEXT_LEG = 2
const SWITCH_BEARINGS_IN_MINUTES = 3
const SWITCH_INCLINATIONS_IN_MINUTES = 4

const chaseSwitch = async () => settingsSwitch(SWITCH_CHASE_LOST_INSTRUMENT)

/**
 * The *Sketching* dialog's eight boxes, in `preferences_sketching.xml`'s order, counted from the
 * end.
 *
 * Five switches now sit above them and the eight no longer fit on one screen with those, so the
 * dialog scrolls and the first of the boxes is below the fold when it opens. Counting from the
 * last one means the caller scrolls to the end — which it has to do anyway — and then asks for a
 * field rather than for a pixel.
 */
const SKETCH_FIELDS = [
  'text-size',
  'symbol-size',
  'line-width',
  'leg-width',
  'splay-width',
  'station-size',
  'station-label-size',
  'legend-size',
]
const sketchField = async (name) => {
  const index = SKETCH_FIELDS.indexOf(name)
  if (index < 0) throw new Error(`no sketching field called ${name}`)
  return numberField(index - SKETCH_FIELDS.length)
}

/**
 * Wheel to the end of the settings dialog, wherever that is — and it moves.
 *
 * This was one wheel of six hundred pixels, which was enough right up until the dialog gained a
 * row. Then it stopped short, the last switch was below the fold, `switchRows()` found one fewer
 * than there are, and every negative index pointed one row too high: the check that meant to turn
 * on *Chase a lost instrument* turned on *Type inclinations in minutes* instead and reported that
 * the setting had not stuck. A fixed scroll is a measurement, and measurements in this file rot —
 * which is the same lesson as the pixel offsets that `switchRows` itself replaced.
 *
 * So: wheel until the picture stops changing. That is what "to the end" means, and it needs no
 * re-tuning when the next setting is added.
 */
const scrollSettingsToTheEnd = async () => {
  await page.mouse.move(210, 500)
  let previous = null
  for (let i = 0; i < 8; i++) {
    await page.mouse.wheel(0, 600)
    await page.waitForTimeout(400)
    const now = (await page.screenshot({ clip: box })).toString('base64')
    if (now === previous) return
    previous = now
  }
}

// Found rather than measured, now that the dialog is tall enough to be capped: its buttons sit a
// fixed distance above its *bottom*, and the bottom is wherever the window puts it.
const settingsSave = async () => {
  const button = await dialogConfirm()
  if (button === null) throw new Error('the settings dialog has no Save button on screen')
  return button
}
// The trip screen, measured off a headless render at 420 by 900 — the first three before anybody
// has been added to the team, the rest after exactly one has, which is the order these checks fill
// it in. `trip_role_*` spell the roles out ("Book (drawing)", "Dog (assistant)"), so the four
// chips wrap onto two rows and the fields below them all moved down.
const TRIP_ADD_NAME = [177, 277]
const TRIP_ADD_BUTTON = [317, 277]
const TRIP_ROLE_BOOK = [137, 317]
const TRIP_INSTRUMENT = [210, 534]
/**
 * The licence box, found by the red it is drawn in.
 *
 * It is below the fold once the team has somebody on it, and by less than the height of its own
 * label — so a measured y was a tap on the line between it and the copyright holder above. It is
 * also the only thing on the screen drawn in the error colour, because `isLicenceChosen` starts
 * false and that is the whole point of the check below: a blank licence is a fine answer but it
 * has to be chosen rather than defaulted into. So the red *is* the handle.
 */
const tripLicenceField = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  const found = await page.evaluate(async ([data]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // Material 3's light `error`, 0xB3261E, and the antialiased shades of it: a strong red
    // channel with very little of either of the others.
    const rows = []
    for (let y = 0; y < c.height; y++) {
      let red = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] > 120 && px[i + 1] < 80 && px[i + 2] < 80) red++
      }
      // A whole border rather than a line of the message underneath it, which is short.
      if (red > 200) rows.push(y)
    }
    return rows
  }, [b64])
  if (found.length === 0) return null
  // Its top border, which is the half of the box that is on screen.
  return [210, found[0] + 12]
}
const LABEL_TEXT = [210, 442]
const LABEL_PLACE = [316, 518]
// The sketch toolbar is nine equal columns; the bottom row's third cell is the label tool.
const toolColumn = box.width / 9
const TOOL_ROW_Y = box.height - 20
const toolCell = (index) => [toolColumn * (index + 0.5), TOOL_ROW_Y]
/**
 * The drawing menu's rows, by name rather than by pixel — for the same reason as the overflow menu.
 *
 * It opens *upwards* from its toolbar cell, so its bottom row stays put and every row above it
 * moves when an item is added. Hard-coded y values for two of these rows had already survived one
 * such addition by silently clicking the wrong item. It is a popup, so it is in the accessibility
 * tree whenever it is open, which is the only time anything here asks for one of its rows.
 */
const DRAWING_MENU_RESOURCES = {
  'delete-last-leg': 'sketch_menu_delete_last_leg',
  centre: 'sketch_menu_centre_view',
  display: 'sketch_toolbar_settings',
}
const drawingMenuRow = (name) => {
  const resource = DRAWING_MENU_RESOURCES[name]
  if (resource === undefined) throw new Error(`no drawing-menu item called ${name}`)
  const label = ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`strings.xml has no ${resource}`)
  return nodeFor(`#${tagFor(label)}`)
}
// The eleven toggles behind that last row, in the order the dialog lists them: `drawing.xml`'s
// `drawingMenuBehaviourToggles` first, then `drawingMenuDisplayToggles`.
//
// `buttonHighlightLatestLeg` is not among them — `pref_highlight_latest_leg` is a sketching
// preference in the Android app, not a drawing-menu toggle, so it lives on that settings screen.
const DRAWING_OPTIONS = [
  'auto-recentre',
  'snap',
  'blue-water',
  'pinch',
  'fade',
  'splays',
  'show-xsections',
  'sketch',
  'grid',
  'labels',
  'north',
]
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
const drawingOptionSwitches = async (below) => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, card, below]) => {
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
    // Stopping above the dialog's own buttons, which is also above the rounded corner beneath
    // them: a card's bottom corners curve away from its bounding box, so the last thirty rows of
    // that box have the *outside* of the dialog in this margin — a long run of not-card, tall
    // enough to be a switch, and reported as a thirteenth row. The corner has always been there;
    // it only became a separate band when the twelfth toggle left and the last row stopped
    // touching it.
    const foot = below ?? bottom
    for (let y = top; y <= Math.min(bottom, foot); y++) {
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
    if (start >= 0) close(Math.min(bottom, foot))
    return { x: Math.round((left + right) / 2), bands }
  }, [b64, DIALOG_CARD, below])
}

/** Where to tap for a named drawing option, with the dialog already open. */
async function drawingOptionRow(name) {
  const index = DRAWING_OPTIONS.indexOf(name)
  if (index < 0) throw new Error(`no drawing option called ${name}`)
  // Bounded above the *Done* button, which is the lowest thing on the card that is not a toggle.
  const buttons = await dialogTextRows()
  const found = await drawingOptionSwitches(buttons.length ? buttons[buttons.length - 1] - 20 : null)
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
/**
 * Flip one switch on the *Sketching* settings screen and save it.
 *
 * `pref_highlight_latest_leg` and the two movement gestures are preferences in the Android app,
 * not drawing-menu toggles — `preferences_sketching.xml` is where they live — so reaching them is
 * a trip through Settings rather than two taps on the toolbar.
 */
async function flipSketchingSwitch(index) {
  await at(...(await overflowButton())); await page.waitForTimeout(500)
  await at(...(await menuRow('sketching'))); await page.waitForTimeout(800)
  await at(...(await settingsSwitch(index))); await page.waitForTimeout(300)
  await at(...(await settingsSave())); await page.waitForTimeout(700)
}

async function toggleOption(name) {
  await at(...chrome('#drawing-menu')); await page.waitForTimeout(500)
  await at(...(await drawingMenuRow('display'))); await page.waitForTimeout(700)
  await at(...(await drawingOptionRow(name))); await page.waitForTimeout(500)
  const done = await dialogConfirm()
  if (done === null) throw new Error('the drawing-options dialog has no Done button on screen')
  await at(...done); await waitForDialogToClose()
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

/**
 * The labelled buttons on the export screen's action row, left to right.
 *
 * Not found the way the chips are. A chip has an outline, so its top edge is a long solid run of
 * not-the-background; a `TextButton` has no outline at all, and its label is a row of glyphs with
 * background between every one of them. So this collects the *columns* that have any ink in the
 * button band and joins ones less than fourteen pixels apart into a label — a gap that swallows
 * the space inside "Save file" and not the fifty-odd pixels of padding between two buttons.
 *
 * Third from the left is *Options*, which only exists while the SVG chip is selected. The status
 * text at the right-hand end of the row is a fourth group and, after a save, several — which is
 * why this counts from the left rather than from the end.
 */
const exportButtons = async () => {
  const chips = await exportChips()
  const row = Math.max(...chips.map(([, y]) => y)) + 52
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, background, y]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const inked = (x) => {
      for (let yy = Math.max(0, y - 11); yy <= Math.min(c.height - 1, y + 11); yy++) {
        const i = (yy * c.width + x) * 4
        if (Math.abs(px[i] - background[0]) > 24 ||
            Math.abs(px[i + 1] - background[1]) > 24 ||
            Math.abs(px[i + 2] - background[2]) > 24) return true
      }
      return false
    }
    const groups = []
    let start = null
    let lastInk = null
    for (let x = 0; x < c.width; x++) {
      if (!inked(x)) continue
      if (start === null || x - lastInk > 14) {
        if (start !== null) groups.push([start, lastInk])
        start = x
      }
      lastInk = x
    }
    if (start !== null) groups.push([start, lastInk])
    return groups
      .filter(([from, to]) => to - from >= 18)
      .map(([from, to]) => [Math.round((from + to) / 2), y])
  }, [b64, EXPORT_BACKGROUND, row])
}

/** *Options*, which is on the row only for the one format it is about. */
const exportOptionsButton = async () => {
  const buttons = await exportButtons()
  if (buttons.length < 3) {
    throw new Error(
      `the export row shows ${buttons.length} button(s), so there is no Options among them` +
        ` (at ${buttons.map((b) => b.join(',')).join(' ')})`)
  }
  return buttons[2]
}

/**
 * *Share survey*, which is on a row of its own below the format actions.
 *
 * Found by looking for the *next* band of ink below the action row, rather than by measuring down
 * a fixed number of pixels from it. The action row is only where it is because the chips wrapped
 * onto three rows on this phone; a constant offset from it would be a second guess stacked on the
 * first, and would come loose the moment a format was added. A band here is a run of scanlines
 * that have any ink at all in them: the band holding the action row is stepped over, and the one
 * after it is this button and the file name beside it.
 */
const exportShareButton = async () => {
  const chips = await exportChips()
  const actions = Math.max(...chips.map(([, y]) => y)) + 52
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  const found = await page.evaluate(async ([data, background, from]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    const ink = (x, y) => {
      const i = (y * c.width + x) * 4
      return Math.abs(px[i] - background[0]) > 24 ||
        Math.abs(px[i + 1] - background[1]) > 24 ||
        Math.abs(px[i + 2] - background[2]) > 24
    }
    const anyInk = (y) => {
      for (let x = 0; x < c.width; x++) if (ink(x, y)) return true
      return false
    }
    // Bands of scanlines with ink in them, from a little above the action row to well below it.
    const bands = []
    let top = null
    for (let y = Math.max(0, from - 16); y <= Math.min(c.height - 1, from + 140); y++) {
      if (anyInk(y)) { if (top === null) top = y } else if (top !== null) { bands.push([top, y - 1]); top = null }
    }
    if (top !== null) bands.push([top, Math.min(c.height - 1, from + 140)])
    const after = bands.filter(([t, b]) => t > from || b < from)
    const below = after.filter(([t]) => t > from)
    if (!below.length) return null
    const [bandTop, bandBottom] = below[0]
    const middle = Math.round((bandTop + bandBottom) / 2)
    const inColumn = (x) => {
      for (let y = bandTop; y <= bandBottom; y++) if (ink(x, y)) return true
      return false
    }
    const groups = []
    let start = null
    let lastInk = null
    for (let x = 0; x < c.width; x++) {
      if (!inColumn(x)) continue
      if (start === null || x - lastInk > 14) {
        if (start !== null) groups.push([start, lastInk])
        start = x
      }
      lastInk = x
    }
    if (start !== null) groups.push([start, lastInk])
    const wide = groups.filter(([f, t]) => t - f >= 18)
    if (!wide.length) return null
    return [Math.round((wide[0][0] + wide[0][1]) / 2), middle]
  }, [b64, EXPORT_BACKGROUND, actions])
  if (!found) {
    throw new Error(`no row of buttons below the export actions at y=${actions}`)
  }
  return found
}

/** Press Save file and read what came out, or null if nothing did. */
const savedExport = async () => {
  const download = await Promise.all([
    page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
    at(...(await exportSaveFile())),
  ]).then(([d]) => d)
  return download === null ? null : readFileSync(await download.path(), 'utf8')
}

/** A finger drag on the canvas, in canvas coordinates. */
async function drag([x0, y0], [x1, y1]) {
  await page.mouse.move(box.x + x0, box.y + y0)
  await page.mouse.down()
  await page.mouse.move(box.x + x1, box.y + y1, { steps: 12 })
  await page.mouse.up()
}
/**
 * The symbol strip: `symbolToolbar`, which is where `activity_graph.xml` keeps the symbols.
 *
 * A `HorizontalScrollView` of button-sized squares between the drawing and the button grid, not a
 * dialog — so it is part of the screen behind any popup, which is exactly what the accessibility
 * tree stops describing once one has been opened. Measured, then, and not asked for. Its first
 * square is the label tool — `Symbol.TEXT`'s place on the app's own strip — then the nineteen UIS
 * symbols in `Symbol.entries` order, then the × that closes it.
 *
 * The toolbar is anchored to the bottom of the screen and the strip is the topmost of its three
 * rows, so the strip sits two button-heights above the tool row wherever the window puts that.
 */
const STRIP_SQUARE = 40
const SYMBOLS = [
  'entrance',
  'gradient',
  'narrow-end',
  'sand',
  'clay',
  'pebbles',
  'blocks',
  'stalactite',
  'stalagmite',
  'pillar',
  'curtain',
  'soda-straw',
  'helictite',
  'crystal',
  'rimstone-dam',
  'water-flow',
  'air-draught',
  'guano',
  'debris',
]
/** Squares on the strip: the label tool, the symbols, then the close cross. */
const STRIP = ['label', ...SYMBOLS, 'close']
const stripRowY = () => TOOL_ROW_Y - 2 * STRIP_SQUARE
/** The nth square, with the strip unscrolled — which is how it opens, every time. */
const stripSquare = (name) => {
  const index = STRIP.indexOf(name)
  if (index < 0) throw new Error(`nothing called ${name} on the symbol strip`)
  return [index * STRIP_SQUARE + STRIP_SQUARE / 2, stripRowY()]
}
/**
 * The nth square once the strip has been dragged to its far end.
 *
 * Twenty-one squares is eight hundred and forty pixels and no phone is that wide, so the symbols
 * past the middle can only be reached by scrolling. Measured back from the right-hand edge rather
 * than forward from a computed scroll offset: at the end of the travel the last square is flush
 * with that edge whatever the window's width and whatever the drag actually moved.
 */
const scrolledStripSquare = (name) => {
  const index = STRIP.indexOf(name)
  if (index < 0) throw new Error(`nothing called ${name} on the symbol strip`)
  return [
    box.width - (STRIP.length - 1 - index) * STRIP_SQUARE - STRIP_SQUARE / 2,
    stripRowY(),
  ]
}
/** `sexyTopoDarkGreen`, which the strip is the only thing on the screen drawn on. */
const SYMBOL_STRIP_GREEN = [0x3a, 0x57, 0x38]
/** Whether the strip is showing, by its own background rather than by counting taps. */
const symbolStripIsOpen = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, green]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    for (let y = 0; y < c.height; y++) {
      let run = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] === green[0] && px[i + 1] === green[1] && px[i + 2] === green[2]) run++
      }
      if (run > c.width * 0.5) return true
    }
    return false
  }, [b64, SYMBOL_STRIP_GREEN])
}
/**
 * Open the strip, however many taps that takes.
 *
 * `buttonSymbol` arms the symbol tool on the first tap and opens the strip on the second, which is
 * what the Android button does — a surveyor stamping the same symbol repeatedly does not want the
 * strip in the way every time. Except the very first time, when it opens straight away, because
 * nobody can pick a symbol from a strip they have not seen. Asking the screen rather than counting
 * taps is what makes this work from either state.
 */
const openSymbolStrip = async () => {
  for (let i = 0; i < 3; i++) {
    if (await symbolStripIsOpen()) return
    await at(...chrome('#symbol-tool'))
    await page.waitForTimeout(500)
  }
  if (!(await symbolStripIsOpen())) throw new Error('the symbol strip would not open')
}
/**
 * Shut it again, so the drawing gets its forty pixels back.
 *
 * Through `buttonSymbol` rather than the strip's own cross: the cross is the last square of
 * twenty-one and is only where `stripSquare` says it is while the strip has not been scrolled,
 * which after reaching for a symbol past the middle it has been.
 */
const closeSymbolStrip = async () => {
  for (let i = 0; i < 3 && (await symbolStripIsOpen()); i++) {
    await at(...chrome('#symbol-tool'))
    await page.waitForTimeout(400)
  }
  if (await symbolStripIsOpen()) throw new Error('the symbol strip would not close')
}
/**
 * Drag it to the far end, for the symbols no phone is wide enough to show at once.
 *
 * Both ways round: a `horizontalScroll` takes a drag, and it takes a wheel, and which of the two a
 * headless browser delivers in a form Compose accepts is not something this file should have to
 * know. What it does check is that the strip moved — a strip that did not scroll leaves the tap on
 * a square that is not the one asked for, and the only sign of that is a symbol that was never
 * stamped, three checks later.
 */
const stripInk = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async ([data, row]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // The squares are drawn pale on the strip's dark green, so anything light in that band is a
    // symbol; weighting by x turns where they fall into one number that changes as it scrolls.
    let ink = 0
    for (let y = row - 18; y < row + 18; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] > 150 && px[i + 1] > 150 && px[i + 2] > 150) ink += x
      }
    }
    return ink
  }, [b64, Math.round(stripRowY())])
}
const scrollSymbolStripToTheEnd = async () => {
  const before = await stripInk()
  for (let i = 0; i < 4; i++) {
    await page.mouse.move(box.x + box.width - 30, box.y + stripRowY())
    await page.mouse.wheel(300, 0)
    await page.waitForTimeout(150)
    await page.mouse.wheel(0, 300)
    await page.waitForTimeout(150)
    await drag([box.width - 30, stripRowY()], [20, stripRowY()])
    await page.waitForTimeout(200)
  }
  if ((await stripInk()) === before) {
    fail('the symbol strip would not scroll, so the symbols past the middle cannot be reached')
  }
}

/**
 * The buttons along the foot of the open dialog, left to right, found rather than measured.
 *
 * `dialogConfirm` measures in from the card's right-hand edge, which is right for the button that
 * is always in the same place whatever it says. The *second* button is not: a dialog lays them out
 * end to end, so where *Cancel* sits depends on how wide the word beside it is — and on how many
 * lines the message above them runs to, which is what moved these when the delete dialog started
 * saying `file_dialog_delete_survey_content` instead of a sentence of this port's own.
 */
const dialogButtons = async () => {
  const rows = await dialogTextRows()
  if (rows.length === 0) return []
  const y = rows[rows.length - 1]
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
    // The same primary the rows themselves are found by, across the whole height of the lettering.
    const lit = new Set()
    for (let row = Math.max(0, y - 8); row <= Math.min(c.height - 1, y + 8); row++) {
      for (let x = 0; x < c.width; x++) {
        const i = (row * c.width + x) * 4
        const [r, g, b] = [px[i], px[i + 1], px[i + 2]]
        if (b > r && r > g && b - g > 30 && b < 230) lit.add(x)
      }
    }
    // Letters of one word are a pixel or two apart; two buttons are twenty or more.
    const xs = [...lit].sort((a, b) => a - b)
    const words = []
    for (const x of xs) {
      if (words.length && x - words[words.length - 1][1] <= 12) words[words.length - 1][1] = x
      else words.push([x, x])
    }
    return words.map(([from, to]) => Math.round((from + to) / 2))
  }, [b64, y])
}
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
/**
 * The cross-section editor's own bar: the activity's title on the left, then `cross_section.xml`'s
 * two `showAsAction="always"` icons on the right where an app bar puts them.
 *
 * Cancel used to be a text button on the far left, which is not where either icon is now. Both are
 * measured in from the right-hand edge so they survive this file's two smaller windows.
 */
const EDITOR_CANCEL = () => [box.width - 71, 15]
// The station menu's rows are found rather than hard-coded. A dialog is centred, so its rows move
// with its height — and this dialog's height depends on the station: the origin has no incoming
// leg and cannot be deleted, so it is two rows shorter than one in the middle of a passage. A
// fixed y worked for whichever station happened to be tested and silently clicked the wrong item
// for any other.
const DIALOG_CARD = [236, 230, 240]
const DIALOG_FIRST_ROW_FROM_TOP = 96

/**
 * Waits for a dialog dismissed a moment ago to actually finish closing, by polling until the
 * screen stops changing rather than trusting that a fixed wait was long enough.
 *
 * Found the hard way: this file used to dismiss dialogs by pressing Escape, which had quietly
 * stopped doing anything at all on this Compose Multiplatform / wasm build - the screenshot taken
 * right after the press-and-wait still showed the dialog fully open, not mid fade-out. Escape (and
 * click-outside) are a desktop-mouse-and-keyboard shortcut that a phone surveyor never has anyway,
 * so every call site now taps the dialog's own button instead, same as a real tap would. This wait
 * is still needed after that tap: the button click starts a genuine close animation, and the very
 * next action in some call sites (a tap on the overflow button behind the dialog's scrim) would
 * otherwise land before the fade finishes and be swallowed by it.
 */
async function waitForDialogToClose() {
  let previous = await page.screenshot({ clip: box })
  for (let i = 0; i < 15; i++) {
    await page.waitForTimeout(200)
    const current = await page.screenshot({ clip: box })
    if (current.equals(previous)) return
    previous = current
  }
}

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
      // 200 pixels of card, not half the *screen*. A dialog card is about 330 wide whatever the
      // screen is — Material does not stretch it — so on a 667-wide landscape phone half the
      // screen is 334 and the card falls a few pixels short of its own detector. All three of
      // these helpers had that threshold and all three missed the same dialog.
      if (cardPixels > 200) {
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
 * One row of the leg's menu, by the action it offers rather than by where it sits.
 *
 * The dialog is a popup, so its rows are in the accessibility tree while it is open, and each
 * carries `tagFor` of the label it is drawn with. Which matters because the order is
 * `legActionsFor`'s and has changed: *Move to Different Station* is below *Delete* now, where the
 * table's own menus put it, and picking the fifth row got the delete confirmation instead.
 *
 * The labels are the splay ones where a splay has its own — `configureMenuVisibility` swaps the
 * comment and the delete titles — so those are named separately.
 */
const LEG_ACTION_RESOURCES = {
  edit: 'menu_edit_leg',
  reverse: 'menu_reverse',
  upgrade: 'menu_upgrade_splay',
  promote: 'menu_promote_to_above_leg',
  downgrade: 'menu_downgrade_leg',
  comment: 'menu_comment_leg',
  'comment-splay': 'menu_comment_splay',
  delete: 'menu_delete_leg',
  'delete-splay': 'menu_delete_splay',
  move: 'menu_move_row',
}
const legActionSelector = (name) => {
  const resource = LEG_ACTION_RESOURCES[name]
  if (resource === undefined) throw new Error(`no leg action called ${name}`)
  const label = ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`strings.xml has no ${resource}`)
  return `#${tagFor(label)}`
}
const legActionRow = (name) => nodeFor(legActionSelector(name))

/**
 * What the open leg menu offers, asked row by row rather than counted.
 *
 * Counting the rows counted the dialog's own *Cancel* along with them, and told nothing about
 * which rows they were: a menu that had gained *Move to Different Station* and lost *Reverse*
 * would have the same number as one that had not.
 */
const legMenuOffers = async (expected, forbidden) => {
  const missing = []
  for (const name of expected) {
    if ((await nodeAt(legActionSelector(name))) === null) missing.push(name)
  }
  const unwanted = []
  for (const name of forbidden) {
    if ((await nodeAt(legActionSelector(name))) !== null) unwanted.push(name)
  }
  return { missing, unwanted }
}

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
      // 200 rather than half the screen — see `dialogTextRows`.
      if (count > 200) return y
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
      // 200 rather than half the screen — see `dialogTextRows`.
      if (count > 200) {
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
  // Thirty-eight in from the card's edge, not thirty. A `TextButton` is at least forty-eight
  // wide however short its label, so this is inside the box for "Add" as well as for "Save" —
  // and thirty was one pixel inside the former, which is not a margin.
  return right === null ? null : [right - 38, rows[rows.length - 1]]
}


// The 3D view's own bar, which now reads like the rest of the app's: the title on the left, then
// *Reset*, then the close cross at the right-hand end where an app bar's actions go.
const THREE_D_CLOSE = () => [box.width - 29, 15]
const EDITOR_DONE = () => [box.width - 29, 15]

// ---- the app is reachable by name, not only by pixel ------------------------------------
// Compose paints the whole app into one canvas, so for a long time the only way to press anything
// here was to work out which pixel it was drawn at. It also builds an accessibility tree of real
// DOM nodes over that canvas — the same one a screen reader reads. Every menu row and every dialog
// row below is asked for by name because of it, so if it ever stopped being built those checks
// would fail together and none of them would say why. This one says why.
//
// It is checked here because here is the last moment the whole app is in it: Compose 1.12.0 keeps
// one semantics owner, and the first popup takes it. See `rememberChrome`.
const namedControls = await namedNodes()
if (!namedControls.some((n) => n.startsWith('#overflow'))) {
  fail(
    'the app is not exposing its accessibility tree, so nothing can be found by name ' +
      `(the page offers: ${namedControls.slice(0, 20).join(', ') || 'nothing'})`)
} else {
  pass(
    'the app names its controls for a screen reader, and for this file ' +
      `(${namedControls.length} of them)`)
}

// The app bar and the toolbar, taken down now, for the reason `rememberChrome` gives.
await rememberChrome('#overflow', '#symbol-tool', '#drawing-menu')

// ---- the app opens on the demo cave, and offers a way out of it ------------------------
// The first screen a new surveyor sees is an example survey that is deliberately never saved.
// Recording controls must not be on it, and something that leads to their own survey must be, or
// the only route off this screen is a three-dot menu.
await page.screenshot({ path: join(shotDir, 'field-first-run.png') })
await at(...START_SURVEYING); await page.waitForTimeout(700)

// That the button leads somewhere usable: the reading dialog is drawn to the canvas, so what is
// checked is that focusing its first field produces the hidden DOM input Compose types through.
await at(...ADD_READING); await page.waitForTimeout(700)
await at(...(await onCard(CARD_DISTANCE))); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-started.png') })
if ((await page.$$('input')).length === 0) {
  fail('the demo cave has no working way through to a survey you can record into')
} else {
  pass('the app opens on the demo cave and offers a way through to your own survey')
}
// Escape does nothing here - diagnostic screenshots proved the dialog was still fully open, not
// mid fade-out, after a press-and-wait. Tap its actual Cancel button instead, as a surveyor would.
await at(...(await cardButton(CARD_CANCEL_X))); await waitForDialogToClose()

// ---- create a named survey -----------------------------------------------------------
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('new'))); await page.waitForTimeout(700)
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
/** A point inside the open reading dialog, from its card's top edge. */
async function onCard([x, downFromTop]) {
  const top = await dialogTop()
  if (top === null) throw new Error('the reading dialog is not open')
  return [x, top + downFromTop]
}

/** One of the dialog's bottom buttons, from the card's bottom edge. */
async function cardButton(x) {
  const top = await dialogTop()
  const height = await dialogHeight()
  if (top === null || height === null) throw new Error('the reading dialog is not open')
  return [x, top + height - CARD_BUTTONS_ABOVE_BOTTOM]
}

/** The nth input-mode chip, in `OFFERED_MODES` order. */
async function modeChip(index) {
  return onCard(CARD_MODES[index])
}

async function reading(d, a, i, { splay = false, mode = null, lrud = null } = {}) {
  await at(...ADD_READING); await page.waitForTimeout(700)
  if (mode !== null) { await at(...(await modeChip(mode))); await page.waitForTimeout(250) }
  await at(...(await onCard(CARD_DISTANCE))); await page.waitForTimeout(200)
  await page.keyboard.type(String(d), { delay: 20 })
  await at(...(await onCard(CARD_AZIMUTH))); await page.waitForTimeout(200)
  await page.keyboard.type(String(a), { delay: 20 })
  await at(...(await onCard(CARD_INCLINATION))); await page.waitForTimeout(200)
  await page.keyboard.type(String(Math.abs(i)), { delay: 20 })
  if (i < 0) { await at(...(await onCard(CARD_SIGN_TOGGLE))); await page.waitForTimeout(200) }
  // Only when `pref_lrud_fields` is on: the boxes are not on the card otherwise, and tapping
  // where they would be would land on the explanation under the mode chips.
  if (lrud !== null) {
    for (const [index, value] of lrud.entries()) {
      if (value === null) continue
      await at(...(await onCard(CARD_LRUD[index]))); await page.waitForTimeout(200)
      await page.keyboard.type(String(value), { delay: 20 })
    }
  }
  await page.waitForTimeout(250)
  await at(...(await cardButton(splay ? CARD_ADD_SPLAY_X : CARD_ADD_LEG_X)))
  await page.waitForTimeout(700)
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
// this checks is that the menu reaches them and that the result is saved with the sketch.
//
// `context_station.xml`, not the drawing menu. `menu_xsection` is a submenu of the station's own
// long-press menu in the Android app, which is the only place that knows *which* station a section
// is being cut at — the drawing menu had to guess from wherever a finger landed.
/**
 * How many rows of the message strip are showing, by its own background colour.
 *
 * `innerPanelBackground`, 0xDDDDDD, which nothing else on this screen is drawn in. The strip sits
 * between the app bar and the drawing rather than over it — an Android toast floats, and a floating
 * message over a cave drawing hides the very thing it is talking about — so it is found by looking
 * for a full-width band of that grey below the app bar.
 */
const noticeRows = async () => {
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
    let rows = 0
    // The app bar is about fifty pixels tall; a hundred is well clear of it and well above
    // anything else this colour.
    for (let y = 0; y < Math.min(140, c.height); y++) {
      let run = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (px[i] === 0xdd && px[i + 1] === 0xdd && px[i + 2] === 0xdd) run++
      }
      if (run > c.width * 0.8) rows++
    }
    return rows
  }, [b64])
}

/**
 * The rows `stationActionsFor` builds for a station on the plan, worked out from what is saved
 * rather than assumed.
 *
 * Which rows a station has depends on the station: the active one cannot be made active, the
 * origin has no leg that made it and cannot be deleted, and `menu_xsection` offers *Create* or the
 * three rows for the section already there. One hard-coded list meant a check that was silently a
 * row out as soon as the survey grew a station somewhere else.
 */
const planStationMenuFor = (name) => page.evaluate((name) => {
  const read = (suffix) => {
    const key = Object.keys(localStorage).find((k) => k.endsWith(suffix))
    return key ? JSON.parse(localStorage.getItem(key)) : null
  }
  const data = read('Swildons.data.json')
  if (!data) return null
  const stations = data.stations ?? []
  const legs = stations.flatMap((station) => station.legs ?? [])
  // "origin is whatever nothing leads to", which is `SurveyJson`'s own rule for finding it.
  const destinations = new Set(legs.map((leg) => leg.destination).filter(Boolean))
  const isOrigin = !destinations.has(name)
  const sections = (read('Swildons.plan.json') ?? {})['x-sections'] ?? []
  const hasSection = sections.some((section) => section['station-id'] === name)

  const rows = []
  if (data.activeStation !== name) rows.push('make-active')
  rows.push('comment', 'rename', 'passage-size')
  if (hasSection) {
    rows.push('cross-section-edit', 'cross-section-set-direction', 'cross-section-delete')
  } else {
    rows.push('cross-section-create')
  }
  if (!isOrigin) rows.push('incoming-leg')
  // `menu_navigate`, less the plan, which is the view this menu was opened in.
  rows.push('show-in-table', 'show-in-elevation')
  if (!isOrigin) rows.push('delete')
  return rows
}, name)

/**
 * A row of the open station menu, by the action it offers.
 *
 * The dialog is a popup, so its rows are in the accessibility tree while it is open, and each
 * carries `tagFor` of the label it is drawn with. Only the rows this file reaches for by name are
 * listed; the rest are still found through `stationMenuRow`, which checks the whole menu against
 * what the survey says the station should be offered.
 */
const STATION_ACTION_RESOURCES = {
  'make-active': 'menu_set_active_station',
  comment: 'menu_comment',
  rename: 'menu_rename_station',
  'passage-size': 'settings_key_lrud_fields_title',
  'draw-left': 'menu_draw_left',
  'draw-right': 'menu_draw_right',
  'draw-vertical': 'menu_draw_vertical',
  'incoming-leg': 'menu_incoming_leg',
  delete: 'menu_delete_station',
}
const stationAction = (name) => {
  const resource = STATION_ACTION_RESOURCES[name]
  if (resource === undefined) throw new Error(`no station-menu row called ${name}`)
  const label = ANDROID_STRINGS[resource]
  if (label === undefined) throw new Error(`strings.xml has no ${resource}`)
  return nodeFor(`#${tagFor(label)}`)
}

/**
 * A row of the open station menu, by name.
 *
 * The rows are `stationActionsFor`'s own list, which depends on where the menu was opened and what
 * the station has — so each call site names the list it expects and this refuses to guess when the
 * menu on screen is not that list. A silently mis-indexed station menu deletes a station instead of
 * commenting on it.
 */
async function stationMenuRow(menu, name) {
  const index = menu.indexOf(name)
  if (index < 0) throw new Error(`no station-menu row called ${name}`)
  const rows = await dialogTextRows()
  // One more row than actions: the dialog's own *Close*, which is drawn in the same primary as
  // the rows above it and sits below the last of them.
  if (rows.length !== menu.length + 1) {
    throw new Error(
      `the station menu shows ${rows.length} rows including Close, not the ` +
        `${menu.length + 1} of ${menu.join(', ')}`)
  }
  return [210, rows[index]]
}

// Station 1 on the plan: the origin, so there is no leg that made it and nothing to delete, and
// the readings above left station 2 active. What rows it has is worked out from the saved survey
// by `planStationMenuFor`, which is also what makes the difference between "before it has a
// section" and "after" a fact rather than a second hard-coded list.
const STATION_ONE = [140, 712]
// Somewhere with no passage on it, which is the whole reason the app asks rather than choosing —
// and far enough from the right-hand edge that the frame drawn round it fits on the screen. A
// section is four metres across, which at this zoom is most of the width of a phone, so a section
// dropped in the middle has its frame cut off by the edge; `handleSpot` then measures the middle
// of what is left of the drag bar rather than the middle of the bar, and the check that the bar
// follows its section fails by exactly half of what the frame overhangs.
const SECTION_GOES_HERE = [180, 520]
const SECTION_PARKED = [210, 660]
// And where it is parked afterwards, which is where every check below this reaches for it. The two
// are different on purpose: a section is drawn inside a frame two hundred and seventy pixels
// across, and a frame round the parking place covers station 1 — so the long press that re-aims it
// would land on the section rather than on the station whose menu it needs.

await longPress(STATION_ONE)
await at(...(await stationMenuRow(await planStationMenuFor('1'), 'cross-section-create')))
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-cross-section-armed.png') })

// `sketch_position_cross_section_instruction`. The row arms a tool and then waits, so a surveyor
// who is not told that is looking at an app that has apparently done nothing.
const askedWhereToDrawIt = await noticeRows()
const sectionsBeforeTheTap = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? []).length
})
if (askedWhereToDrawIt < 4) {
  fail(`choosing "Create Cross Section" put up no instruction (${askedWhereToDrawIt} rows of it)`)
} else if ((sectionsBeforeTheTap ?? 0) !== 0) {
  fail(`"Create Cross Section" drew the section itself instead of asking (${sectionsBeforeTheTap})`)
} else {
  pass('creating a cross-section asks where to draw it rather than choosing for the surveyor')
}

// Out of the way before the tap that places it, so the message strip is not still pushing the
// drawing down when the coordinates below are taken.
await page.waitForTimeout(2800)
await at(...SECTION_GOES_HERE); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-cross-section.png') })

const sections = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? []).length
})
if (sections === null) {
  fail('the plan sketch was not saved, so the cross-section could not be checked')
} else if (sections < 1) {
  fail('tapping the paper with the cross-section tool armed did not add one')
} else {
  pass('a cross-section is drawn where the surveyor put it, and is saved with the sketch')
}

// ---- and the tool is a one-shot, as the app's is ---------------------------------------------
// `handlePositionCrossSection` ends with `setSketchTool(previousSketchTool)`. A tool that stayed
// armed would put a second section down under the next stroke the surveyor drew, which is a
// drawing they have to undo without knowing why it happened.
// Counted inline rather than through `planStrokes`: that is a `const` declared several hundred
// lines below, and `const` is not hoisted.
const sketchPaths = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key)).paths ?? []).length
})
const strokesBeforeNextTap = await sketchPaths()
// Well clear of the frame just drawn: a tap inside a section opens its editor, which would be a
// second thing happening and would leave that editor over everything below.
await at(380, 200); await page.waitForTimeout(700)
const sectionsAfterNextTap = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? []).length
})
if (sectionsAfterNextTap !== sections) {
  fail(`the cross-section tool stayed armed and put down a second section (${sectionsAfterNextTap})`)
} else {
  pass('positioning a section is one tap, and hands the previous tool back')
}
if ((await sketchPaths()) !== strokesBeforeNextTap) {
  fail('the tap after a section was placed drew on the sketch')
}

// ---- and can be corrected afterwards ---------------------------------------------------
// The bearing is a guess — `CrossSectioner` bisects the corner mid-passage and falls back to north
// — and a section cutting the passage at the wrong angle is not a rough drawing, it is a wrong
// one. `action_xsection_set_direction` is how a surveyor overrules it, and the gesture wiring it
// arms is exactly the sort of thing that can silently do nothing. (It has: the symbol tool stamped
// nothing at all for a while, because a drag detector never fires for a tap.)
//
// There is no *Move a cross-section* to check any more, and there should not be: `GraphView`
// hit-tests the drag bar on every touch-down whatever tool is in hand, which the next check is.
const firstSection = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key))['x-sections'] ?? [])[0] ?? null
})

const placedSection = await firstSection()

await longPress(STATION_ONE)
await at(...(await stationMenuRow(await planStationMenuFor('1'), 'cross-section-set-direction')))
await page.waitForTimeout(700)
// Grab the section where it was put down and swing it round its station.
await drag(SECTION_GOES_HERE, [SECTION_GOES_HERE[0] + 90, SECTION_GOES_HERE[1] + 80])
await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-cross-section-aimed.png') })

const aimedSection = await firstSection()
if (!placedSection || !aimedSection) {
  fail('the plan sketch was not saved, so re-aiming a cross-section could not be checked')
} else if (aimedSection.angle === placedSection.angle) {
  fail('dragging a cross-section after "Set Direction" did not change its bearing')
} else if (
  aimedSection.location.x !== placedSection.location.x ||
  aimedSection.location.y !== placedSection.location.y
) {
  fail('re-aiming a cross-section also moved it')
} else {
  pass('a cross-section can be re-aimed when the bearing the app guessed is wrong')
}

// ---- and picked up by the bar it is drawn with -------------------------------------------
// Every section is drawn with a green bar across the top of its frame, three grip marks down the
// middle: the universal "this is a thing you drag". The port drew it from the day the frame went
// in and never hit-tested it, so the one affordance a section has did nothing, and moving one
// meant knowing to open the drawing menu and pick "Move a cross-section" first. `GraphView` needs
// no such thing - it hit-tests the bar on every touch-down, whatever tool is in hand.
//
// Checked with the *pencil* selected, because that is the case that has to work and the case that
// can silently fail: the bar's detector and the drawing detector are two gesture loops over the
// same pixels, and getting this wrong either leaves the section where it was or draws a line
// across the cave while moving it. Both are checked.
const handleSpot = async () => {
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
    // `crossSectionFrame`: 0xFF7FAF7F. Deliberately the app bar's own green, so the frame reads
    // as chrome rather than as something the surveyor drew - which is why the scan is bounded to
    // the sketch, with the app bar above it and the toolbar below.
    const rows = new Map()
    for (let y = top; y < bottom; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        if (Math.abs(px[i] - 0x7f) < 12 && Math.abs(px[i + 1] - 0xaf) < 12 &&
            Math.abs(px[i + 2] - 0x7f) < 12) {
          if (!rows.has(y)) rows.set(y, [])
          rows.get(y).push(x)
        }
      }
    }
    if (rows.size === 0) return null
    // Counted, not measured end to end: the frame is a stroked rectangle, so *every* row of it
    // has green at the left edge and green at the right edge and spans the whole width. What
    // separates the filled bar from the hairline sides is how many pixels are green in between.
    const most = Math.max(...[...rows.values()].map((xs) => xs.length))
    const filled = [...rows.entries()].filter(([, xs]) => xs.length > most / 2).map(([y]) => y)
    const barTop = Math.min(...filled)
    const xs = rows.get(barTop)
    return [Math.round((Math.min(...xs) + Math.max(...xs)) / 2), barTop + 4]
    // The app bar and the toolbar are the same green as the frame, which is the point of the
    // colour - so the scan is bounded to the sketch between them. Measured rather than guessed:
    // the bar runs 0 to 51 and the toolbar starts eighty pixels off the bottom.
  }, [b64, 60, Math.round(box.height) - 100])
}

const planPathCount = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  return (JSON.parse(localStorage.getItem(key)).paths ?? []).length
})

await at(...toolCell(1)); await page.waitForTimeout(500)
const grip = await handleSpot()
const strokesBeforeGrab = await planPathCount()
if (!grip) {
  fail('no cross-section frame was found on the plan, so its drag bar could not be tried')
} else {
  await drag([grip[0], grip[1]], [grip[0] + 70, grip[1] - 60]); await page.waitForTimeout(900)
  await page.screenshot({ path: join(shotDir, 'field-cross-section-handle.png') })
  const grabbed = await firstSection()
  const strokesAfterGrab = await planPathCount()
  if (!grabbed) {
    fail('the plan sketch was not saved, so the cross-section drag bar could not be checked')
  } else if (
    grabbed.location.x === aimedSection.location.x &&
    grabbed.location.y === aimedSection.location.y
  ) {
    fail('dragging a cross-section by its drag bar did not move it')
  } else if (strokesAfterGrab !== strokesBeforeGrab) {
    fail('moving a cross-section by its bar drew a line across the plan as well')
  } else if (grabbed.angle !== aimedSection.angle) {
    fail('moving a cross-section by its bar also changed its bearing')
  } else {
    pass('a cross-section can be picked up by its drag bar without changing tools')
  }

  // And the bar goes where the section went. The rectangles the hit test reads are filled in by
  // the draw pass and cleared at the start of every frame, and "cleared" is the half that has no
  // other symptom: a map that only ever grew would leave last frame's bar live at last frame's
  // coordinates, and a second grab would pick up a section that is no longer in the sketch.
  const movedGrip = await handleSpot()
  if (!movedGrip) {
    fail('the drag bar vanished after the section was dropped')
  } else if (
    Math.abs(movedGrip[0] - (grip[0] + 70)) > 8 ||
    Math.abs(movedGrip[1] - (grip[1] - 60)) > 8
  ) {
    fail(`the drag bar stayed where the section used to be (${grip} then ${movedGrip})`)
  } else {
    pass('the drag bar follows the section it belongs to')
    // Put it back where it was — by the bar, which is the gesture being tested, so the restoring
    // drag is a second run of it.
    await drag([movedGrip[0], movedGrip[1]], [movedGrip[0] - 70, movedGrip[1] + 60])
    await page.waitForTimeout(900)
  }
}

// And then park it where the rest of this file expects to find it: hiding it, tapping through it
// and opening its editor are all done by coordinate, and those were measured with the section
// here. Moved by its own bar, which is the only thing that moves one.
const parkingGrip = await handleSpot()
if (parkingGrip === null) {
  fail('the section could not be parked, because its drag bar was not found')
} else {
  await drag(parkingGrip, [
    parkingGrip[0] + SECTION_PARKED[0] - SECTION_GOES_HERE[0],
    parkingGrip[1] + SECTION_PARKED[1] - SECTION_GOES_HERE[1],
  ])
  await page.waitForTimeout(900)
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

// A tight box round where the section now sits — `SECTION_PARKED`, which the drag bar put it at.
// Tight because at this point in the run the section is only its centre dot: it was dropped at a
// station whose wall shots are not booked until much later, so it has no arms to draw yet, and the
// whole of it is twenty pixels across.
const sectionPatch = () => [
  SECTION_PARKED[0] - 16,
  SECTION_PARKED[1] - 8,
  SECTION_PARKED[0] + 4,
  SECTION_PARKED[1] + 16,
]
// The purple of the "Add reading" pill, sampled off the pill rather than off the white lettering
// across the middle of it.
const FIELD_BAR_PILL = [40, 790]

const withSection = await inkAround(sectionPatch())
await toggleOption('show-xsections')
await page.screenshot({ path: join(shotDir, 'field-cross-section-hidden.png') })

const withoutSection = await inkAround(sectionPatch())
// Not "the window is empty", which is what this used to ask. The station keeps its cross-section
// indicator when sections are hidden, because the Android app keeps it: only `drawCrossSections`
// is behind the toggle, while `drawStations` reads the sketch directly and draws the mark for any
// station carrying a section. That is useful rather than a slip - with the sections cleared off
// the page you can still see which stations have one - and it is why the port reproduces it.
//
// The mark is a metre-long line through the station with a thin arrowhead, and here the section
// was dropped so near its own station that the arrowhead's tip clips the corner of this window:
// sixteen units of ink against the section's own two and a half thousand. So what is asserted is
// that essentially all of the section's ink goes. A section still being drawn would leave
// hundreds, and fail this as squarely as it failed the old form.
if (!(withSection > 100 && withoutSection * 20 < withSection)) {
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

await at(...SECTION_PARKED); await page.waitForTimeout(900)
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
await at(...SECTION_PARKED); await page.waitForTimeout(1000)
await page.screenshot({ path: join(shotDir, 'field-cross-section-editor.png') })

const subSketchPaths = async () => {
  const section = await firstSection()
  return ((section?.sketch ?? {}).paths ?? []).length
}
const planPaths = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  return key ? (JSON.parse(localStorage.getItem(key)).paths ?? []).length : -1
})

await drag([120, 400], [300, 400]); await page.waitForTimeout(400)
await drag([300, 400], [300, 560]); await page.waitForTimeout(400)
await page.screenshot({ path: join(shotDir, 'field-cross-section-drawn.png') })
await at(...EDITOR_DONE()); await page.waitForTimeout(1000)
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
await at(...SECTION_PARKED); await page.waitForTimeout(1000)
await drag([120, 300], [300, 300]); await page.waitForTimeout(400)
await at(...EDITOR_CANCEL()); await page.waitForTimeout(1000)

if ((await subSketchPaths()) !== 2) {
  fail('cancelling the cross-section editor kept the stroke anyway')
} else if ((await planPaths()) !== planPathsBefore) {
  fail('the cross-section editor never opened — the stroke went onto the plan instead')
} else {
  pass('a stroke abandoned in the cross-section editor leaves both the section and the plan alone')
}

// ---- and a colour can be chosen inside it, same as on the plan --------------------------------
// `CrossSectionActivity.disableUnsupportedTools` hides only *Select* — every other tool the
// Android app offers here, including the full colour row, this port had simply never wired up.
// The row sits one 40dp button-height above the tools row this file already reaches by name.
// Nine cells, not eight: the editor's colour row is the plan's, which is the eight brushes and
// then zoom-in, because `disableUnsupportedTools` takes away *Select* and nothing else.
const CROSS_SECTION_COLOUR_ROW_Y = box.height - 60
const crossSectionColourCell = (index) => [(box.width / 9) * (index + 0.5), CROSS_SECTION_COLOUR_ROW_Y]
await at(...SECTION_PARKED); await page.waitForTimeout(1000)
await at(...crossSectionColourCell(3)); await page.waitForTimeout(400) // red
await drag([120, 330], [300, 330]); await page.waitForTimeout(400)
await at(...EDITOR_DONE()); await page.waitForTimeout(1000)

const lastSectionStrokeColour = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.plan.json'))
  if (!key) return null
  const sections = JSON.parse(localStorage.getItem(key))['x-sections'] ?? []
  const paths = sections[0]?.sketch?.paths ?? []
  return paths.length ? paths[paths.length - 1].colour : null
})

if (lastSectionStrokeColour !== 'RED') {
  fail(
    `a stroke drawn after picking red in the cross-section editor was saved as ` +
      `${lastSectionStrokeColour}`)
} else {
  pass('a colour can be chosen inside the cross-section editor, and the stroke keeps it')
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

// ---- a tap of the pencil leaves a dot -------------------------------------------------------
// `GraphView.handleDraw`'s ACTION_UP branch opens `if (touchPointOnView.equals(actionDownPoint))
// { // handle dots`, so on Android a press and lift without movement leaves a mark. Here the draw
// tool was a `detectDragGestures`, which waits for the touch slop before firing anything at all —
// so a tap produced no stroke, while `finishPath`'s own comment went on saying that a stroke of
// fewer than two points is committed "because a tap is how you draw a dot". It is. Nothing ever
// asked it to.
const pathsBeforeDot = await planPaths()
await at(150, 300); await page.waitForTimeout(600)
const pathsAfterDot = await planPaths()

if (pathsBeforeDot < 0) {
  fail('the plan was not saved, so the dot could not be checked')
} else if (pathsAfterDot !== pathsBeforeDot + 1) {
  fail(
    `a tap with the pencil left ${pathsAfterDot - pathsBeforeDot} strokes, not one` +
      ' — the drag detector swallowed it, as it did the symbol tool before it')
} else {
  pass('a tap of the pencil leaves a dot, which a drag detector on its own cannot do')
}

// ---- and a drag does not lose the ground it covered before Compose called it one --------------
// Reported directly, off an iPad: a stroke drawn with the Apple Pencil sometimes missed its own
// start. `detectDragGestures` only calls onDragStart once the touch has moved past its own slop
// threshold, with wherever the pointer had reached by *then* — not the original touch-down — so
// the line always began a few pixels late. A finger dragged a long way barely notices; a Pencil's
// small, precise, momentarily-still first contact does.
//
// A short, slow drag makes the lost ground a large fraction of the whole stroke rather than a
// sliver of a long one, so it shows up as a clear miss rather than a rounding error: ink is
// measured in a small box sat exactly on the touch-down pixel, which the old code left bare no
// matter how far the drag went, because nothing was ever drawn that close to the actual start.
const STROKE_START = [300, 260]
const startBox = [STROKE_START[0] - 5, STROKE_START[1] - 5, STROKE_START[0] + 5, STROKE_START[1] + 5]
const inkAtStartBefore = await inkAround(startBox)

await page.mouse.move(box.x + STROKE_START[0], box.y + STROKE_START[1])
await page.mouse.down()
await page.mouse.move(box.x + STROKE_START[0] + 60, box.y + STROKE_START[1], { steps: 20 })
await page.mouse.up()
await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'field-pencil-start.png') })
const inkAtStartAfter = await inkAround(startBox)

if (!(inkAtStartAfter > inkAtStartBefore + 40)) {
  fail(
    `a stroke dragged from (${STROKE_START}) left no ink on its own starting pixel ` +
      `(${inkAtStartBefore} before, ${inkAtStartAfter} after) — the line began late`)
} else {
  pass('a drag starts drawing from the touch-down point, not from wherever slop was crossed')
}

// ---- and the rubber rubs, rather than only lifting what it landed on -------------------------
// The one deliberate departure from the Android app in this file's sketching checks, so it is
// worth being plain about: `GraphView.handleErase` does its work under `case ACTION_DOWN` and its
// `ACTION_MOVE` case is a bare `break`, so over there dragging the eraser across a wall does
// nothing at all. This port copied that faithfully. It is still wrong — a tool drawn as an eraser
// and named *Erase* is one every surveyor will try to rub with — so here a drag erases everything
// it passes over.
//
// Five separate strokes, then one drag across all of them. A tap-only eraser can take at most one.
await at(...toolCell(1)); await page.waitForTimeout(400)
for (let i = 0; i < 5; i++) {
  await drag([100 + i * 30, 240], [100 + i * 30, 300])
  await page.waitForTimeout(300)
}
const pathsBeforeRub = await planPaths()

await at(...toolCell(3)); await page.waitForTimeout(400)
await drag([95, 270], [235, 270]); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-rubbed.png') })
const pathsAfterRub = await planPaths()
const rubbedOut = pathsBeforeRub - pathsAfterRub

// Rubbing the middle out of a stroke *splits* it rather than deleting it, so the count can rise as
// well as fall — which is `pref_delete_path_fragments`, on by default, and is why this counts the
// strokes it crossed rather than the strokes that remain.
const crossedByTheRub = 5
if (pathsBeforeRub < crossedByTheRub) {
  fail(`only ${pathsBeforeRub} strokes were drawn, so the rub cannot show anything`)
} else if (rubbedOut < 2) {
  fail(
    `a drag across ${crossedByTheRub} strokes changed ${rubbedOut} of them — the eraser is still` +
      ' only lifting what it landed on')
} else {
  pass(`the rubber rubs: one drag across ${crossedByTheRub} strokes took out ${rubbedOut}`)
}

// ---- what it rubs disappears as it goes, not only once the finger lifts ----------------------
// The strokes *were* being erased as the finger crossed them, but nothing told the canvas to look
// again until the whole drag finished — so a rub across five strokes looked like nothing had
// happened right up to the moment the finger came up. A fresh band, clear of the rub above.
await at(...toolCell(1)); await page.waitForTimeout(400)
for (let i = 0; i < 5; i++) {
  await drag([100 + i * 30, 340], [100 + i * 30, 400])
  await page.waitForTimeout(300)
}
const RUB_BAND = [95, 340, 235, 400]
const inkBeforeLiveRub = await inkAround(RUB_BAND)

await at(...toolCell(3)); await page.waitForTimeout(400)
await page.mouse.move(box.x + 95, box.y + 370)
await page.mouse.down()
await page.mouse.move(box.x + 165, box.y + 370, { steps: 6 })
await page.waitForTimeout(400)
const inkMidRub = await inkAround(RUB_BAND)
await page.mouse.move(box.x + 235, box.y + 370, { steps: 6 })
await page.mouse.up()
await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-rubbing-live.png') })
const inkAfterFullRub = await inkAround(RUB_BAND)

if (!(inkBeforeLiveRub > 0)) {
  fail('the strokes for the live-redraw check were never drawn')
} else if (!(inkMidRub < inkBeforeLiveRub * 0.9)) {
  fail(
    `the eraser had only reached halfway across but the drawing still showed ${inkMidRub} of its ` +
      `original ${inkBeforeLiveRub} — it is still waiting for the finger to lift before it redraws`)
} else {
  pass(`the rubber shows what it has erased as it goes (${inkBeforeLiveRub} to ${inkMidRub} halfway across)`)
}

// ---- and one drag is one undo, however many strokes it crossed --------------------------------
// Each stroke the eraser crossed used to become its own undo step, so taking back a five-stroke
// rub took five presses of ctrl+z. It should be the one gesture it was.
await page.keyboard.press('Control+z')
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-rub-undone.png') })
const inkAfterOneUndo = await inkAround(RUB_BAND)

if (!(inkAfterFullRub < inkBeforeLiveRub * 0.3)) {
  fail(`the rub did not clear enough of the band to test undo (${inkBeforeLiveRub} then ${inkAfterFullRub})`)
} else if (!(inkAfterOneUndo > inkBeforeLiveRub * 0.85)) {
  fail(
    `one ctrl+z after a drag across five strokes only brought back ${inkAfterOneUndo} of the ` +
      `original ${inkBeforeLiveRub} — it undid one stroke rather than the whole drag`)
} else {
  pass(
    `one undo takes back a whole drag of the eraser, not just the last stroke it crossed ` +
      `(${inkAfterFullRub} back to ${inkAfterOneUndo} of ${inkBeforeLiveRub})`)
}

await at(...toolCell(1)); await page.waitForTimeout(400)

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
await at(...chrome('#drawing-menu')); await page.waitForTimeout(500)
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
    const stationMarks = []
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
        // `station`: 0x8B0000. The centreline stops a little short of the station it ends at
        // (leaving room for the cross the station is drawn as), so the farthest centreline pixel
        // alone lands a surveyor's finger a station-width away rather than on the station.
        if (r > 120 && r < 160 && g < 40 && b < 40) stationMarks.push([x, y])
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
    if (Math.sqrt(bestDistance) <= 60) return { active, other: null }
    // The station itself, if its cross is close enough to the line's end to be the one it drew:
    // sharper than the endpoint pixel alone, and inside the touch reach a long press needs to
    // land within.
    const nearby = stationMarks.filter(([x, y]) => (x - best[0]) ** 2 + (y - best[1]) ** 2 <= 60 ** 2)
    return { active, other: mean(nearby) ?? best }
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

// Off for these two checks: a cross-section's own tap target is deliberately generous — at least
// a metre in every direction, per `boundsOf(CrossSectionDetail)` — so one dropped near this
// station earlier in the run can cover it completely, and a press meant for the long-press menu
// would land on the section instead. Hidden sections cannot be tapped at all, which is exactly
// what a surveyor already has the toggle for when a section is in the way of something else on
// the plan. It also keeps `stationSpots` from mistaking the section's own dropped marker — drawn
// in the station colour too — for the station itself. Back on before anything after this expects
// the app's own default.
await toggleOption('show-xsections')

// `buttonCentreView` centres the view on the active station and keeps the zoom the surveyor
// chose, which is what `centreViewOnActiveStation` does — it does not fit the whole cave on the
// screen, which is what this port used to do under the same menu row. So after the corner-pan
// checks above there is no promise that a *second* station is on the paper at all, and this check
// needs one. Zoom out until there is, which is what a surveyor looking for one would do.
//
// Not merely on the paper: reachable. A station drawn against the bottom edge of the sketch is
// half off it, so the mean of its cross sits above where the station actually is, and a press
// aimed at the mean can miss the station entirely. A finger's width of margin is what a surveyor
// would want too.
//
// Best effort, not a precondition: `zoomOut` is 0.9 of the scale a tap, which the app can refuse
// at the limits, and a station that is on the paper at all is usually pressable. So the loop says
// what each tap did and then the check goes ahead with wherever the station ended up — a press
// that lands on nothing is reported by the menu that does not open, which is a more useful
// sentence than this one refusing to try.
const REACHABLE_MARGIN = 40
const reachable = (spot) =>
  spot !== null &&
  spot[0] > REACHABLE_MARGIN &&
  spot[0] < box.width - REACHABLE_MARGIN &&
  spot[1] > sketchTop + REACHABLE_MARGIN &&
  spot[1] < sketchBottom - REACHABLE_MARGIN
// The sketch toolbar's last cell — `buttonZoomOut`, the same arithmetic every other tap on that
// row uses.
const TOOL_ZOOM_OUT = 8
let spots = await stationSpots(sketchTop, sketchBottom)
for (let i = 0; i < 6 && !reachable(spots.other); i++) {
  await at(...toolCell(TOOL_ZOOM_OUT))
  await page.waitForTimeout(600)
  const after = await stationSpots(sketchTop, sketchBottom)
  console.log(
    `      zoomed out: ${JSON.stringify(spots.other)} -> ${JSON.stringify(after.other)} ` +
      `(sketch ${sketchTop}..${sketchBottom})`)
  spots = after
}

if (!spots.other) {
  fail(`could not find a station on the plan that is not the active one (${JSON.stringify(spots)})`)
} else {
  const activeBefore = await page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
    return key ? JSON.parse(localStorage.getItem(key)).activeStation : null
  })
  const strokesBefore = await planStrokes()

  await page.screenshot({ path: join(shotDir, 'field-station-before-hold.png') })
  await longPress(spots.other)
  await page.screenshot({ path: join(shotDir, 'field-station-menu.png') })

  const menuTop = await dialogTop()
  if (menuTop === null) {
    fail(`holding a station did not open its menu (pressed ${JSON.stringify(spots.other)})`)
    // A press that did not become a long press is a plain tap, and a tap near a cross-section
    // opens its editor over everything that follows — so back out of whatever did happen.
    await at(...EDITOR_CANCEL()); await page.waitForTimeout(600)
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
  // The check above has just made this station the active one, so *Set Active* is gone from its
  // menu and every row below it has moved up. Which row *Show it in the table* is depends on the
  // station, so it is worked out from the saved survey rather than counted.
  const held = await page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
    return key ? JSON.parse(localStorage.getItem(key)).activeStation : null
  })
  await at(...(await stationMenuRow(await planStationMenuFor(held), 'show-in-table')))
  await page.waitForTimeout(1000)
  await page.screenshot({ path: join(shotDir, 'field-jumped-to-table.png') })

  if (!(await onTheTable())) {
    fail('"show it in the table" did not take the surveyor to the table')
  } else {
    pass('a station held on the drawing can send you to its row in the table')
  }
  await at(...PLAN_TAB); await page.waitForTimeout(700)
}

// Back on, because everything after this expects the app's own default.
await toggleOption('show-xsections')

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
// `action_find_station` is in `tools_group_edit`, not on the drawing menu. Nought saved surveys
// passed deliberately: only the two pages that list the library shift with it, and Tools is not
// one of them.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('find'))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-find-station.png') })

if ((await dialogTop()) === null) {
  fail('the find-a-station dialog did not open')
} else {
  const rows = await dialogTextRows()
  // The last row is the dialog's own Close button; the ones above it are the stations.
  //
  // This quietly depends on the name box being *unfocused*, which is worth knowing: a Material
  // OutlinedTextField floats its label up onto its own border when it gains focus, so a focused
  // box contributes a row of writing above the list and `rows[0]` stops being a station. That is
  // exactly what happened when every typing dialog was given an opening focus - the tap landed in
  // the text box, the dialog stayed open, and the failure surfaced two hundred lines later as
  // "the reading dialog is not open". FindStationDialog is not focused on open, for a reason
  // written down there: it is a list as much as a field.
  if (rows.length < 2) {
    fail(`the find dialog listed no stations (${rows.length} rows)`)
    // The last row is the dialog's own Close button (see the comment above) - tap it directly,
    // same as everywhere else in this file, rather than a key the dialog does not act on.
    if (rows.length === 1) { await at(210, rows[0]); await waitForDialogToClose() }
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
  await at(...chrome('#drawing-menu')); await page.waitForTimeout(500)
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
/**
 * Every station in every saved survey, as `survey/station`.
 *
 * Across all of them rather than out of `Swildons` by name, because which survey is open depends on
 * where in this file you are: the import checks open other ones. A check that names the survey it
 * expects reads an empty list further down and reports it as "the leg made no station", which is
 * both wrong and the hardest kind of wrong to read.
 */
const savedStationNames = () => page.evaluate(() => {
  const names = []
  for (const key of Object.keys(localStorage)) {
    if (!key.endsWith('.data.json')) continue
    const survey = key.slice(key.lastIndexOf('/') + 1).replace('.data.json', '')
    let parsed = null
    try { parsed = JSON.parse(localStorage.getItem(key)) } catch (e) { parsed = null }
    for (const station of parsed?.stations ?? []) names.push(`${survey}/${station.name}`)
  }
  return names.sort()
})

/** How many surveys the library holds, which is what shifts the File page's rows. */
const savedSurveyCount = () => page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})

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
  // Row 1 is the leg to station 2; row 2 is the splay. Held rather than tapped: `onRowLongClick`
  // is what opens a row's menu in the Android app, and a tap edits the reading — which is what
  // this port does now, and what it did not do when this check was written.
  await longPress(await tableRow(1)); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-leg-actions.png') })

  // What a splay is offered, and what it is not. A splay cannot be reversed — reverseLeg is
  // addressed by the station a leg arrives at, and a splay arrives nowhere — and it is already
  // what a downgrade would make it. Getting this wrong is not a cosmetic fault: every one of
  // these buttons rewrites the survey.
  const splayMenu = await legMenuOffers(
    ['edit', 'upgrade', 'promote', 'comment-splay', 'delete-splay', 'move'],
    ['reverse', 'downgrade'],
  )
  if (splayMenu.missing.length > 0 || splayMenu.unwanted.length > 0) {
    fail(
      `a splay's menu is missing ${splayMenu.missing.join(', ') || 'nothing'} and ` +
        `should not offer ${splayMenu.unwanted.join(', ') || 'nothing'}`)
  } else {
    pass('a splay is offered every way of promoting it, and neither of the leg-only actions')
  }

  // ---- and a shot booked off the wrong station can be re-hung -----------------------------
  // The mistake this repairs is not a mistyped number: it is a shot taken from the wrong place,
  // which happens whenever somebody pushes on from the end of the passage when the leg should
  // have come off the junction behind them. Until this existed the only repair was Delete, and
  // for a connecting leg that takes everything surveyed beyond it too.
  //
  // The station the splay hangs off is read from the saved file rather than from the table, so
  // this says what actually happened to the survey and not what the screen drew.
  const splayHost = () => page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
    if (!key) return null
    const stations = JSON.parse(localStorage.getItem(key)).stations ?? []
    const owner = stations.find((st) => (st.legs ?? []).some((leg) => leg.destination === '-'))
    return owner ? owner.name : null
  })

  const hostBefore = await splayHost()
  await at(...(await legActionRow('move'))); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-move-leg.png') })
  // The picker is the explanation, the Name box, one row per candidate station, then Cancel — so
  // the first text row is the first station it will let you move to, and on this survey the only
  // one: the splay is off station 1, and a splay leads nowhere, so every other station is fair
  // game. A connecting leg would have its own subtree missing from this list.
  const pickerRows = await dialogTextRows()
  if (pickerRows.length < 2) {
    fail(`the move-leg picker offered no station to move to (${JSON.stringify(pickerRows)})`)
  } else {
    await at(ACT_X, pickerRows[0]); await page.waitForTimeout(900)
    const hostAfter = await splayHost()
    const stillThere = (await savedLegs()).filter(isSplay).length
    if (hostAfter === hostBefore) {
      fail(`re-hanging the shot left it on station ${hostAfter}`)
    } else if (stillThere !== 1) {
      fail(`re-hanging the shot did not move it, it copied or lost it (${stillThere} splays)`)
    } else if (!(await savedLegs()).some(isConnecting)) {
      fail('re-hanging the shot took the connected leg with it')
    } else {
      pass(`a shot booked off the wrong station can be re-hung (${hostBefore} to ${hostAfter})`)
    }
  }

  // Re-hanging the shot closed the actions dialog, as every action that rewrites the survey does,
  // so the edit below needs it opened again. The splay is still the second row: the table is in
  // survey order and it now hangs off station 2 rather than station 1, which is the row after the
  // leg that makes station 2 either way.
  await longPress(await tableRow(1)); await page.waitForTimeout(700)
  await at(...(await legActionRow('edit'))); await page.waitForTimeout(700)
  await page.screenshot({ path: join(shotDir, 'field-edit-open.png') })
  // By name. Counting boxes down the card put the click somewhere that was not the distance box,
  // and a click inside the card that is not in a field dismisses the editor — which took the run
  // back to the menu behind it and left the reading as it was.
  await retype(await nodeFor('#reading-distance'), '2.75')
  await page.screenshot({ path: join(shotDir, 'field-edit-reading.png') })
  await at(...(await dialogConfirm())); await page.waitForTimeout(900)

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
  await longPress(await tableRow(1)); await page.waitForTimeout(700)
  await at(...(await legActionRow('delete-splay'))); await page.waitForTimeout(600)
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

const inkBefore = await distanceInk((await tableRow(0))[1])

await longPress(await tableRow(0)); await page.waitForTimeout(700)
// `context_leg.xml`'s own order, which is Edit, then the rows that change the survey, then the
// comment, with Delete in a group of its own below a divider. The comment used to be second,
// above the five rows that rewrite the survey, which is not where the app puts the row nobody is
// in a hurry to reach.
const legMenu = await legMenuOffers(
  ['edit', 'reverse', 'downgrade', 'comment', 'delete'],
  ['upgrade', 'promote'],
)
if (legMenu.missing.length > 0 || legMenu.unwanted.length > 0) {
  fail(
    `a leg with nothing beyond it is missing ${legMenu.missing.join(', ') || 'nothing'} and ` +
      `should not offer ${legMenu.unwanted.join(', ') || 'nothing'}`)
} else {
  pass('a leg with nothing surveyed beyond it can be taken back down to a splay')
}
await at(...(await legActionRow('comment'))); await page.waitForTimeout(700)
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
const inkAfter = await distanceInk((await tableRow(0))[1])
if (!(inkAfter > inkBefore)) {
  fail(`the table shows no marker against the commented leg (ink ${inkBefore} then ${inkAfter})`)
} else {
  pass('and the table marks the row, so a comment can be found without opening anything')
}

// ---- reversing it, and reversing it back ---------------------------------------------------
await longPress(await tableRow(0)); await page.waitForTimeout(700)
await at(...(await legActionRow('reverse'))); await page.waitForTimeout(900)

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

await longPress(await tableRow(0)); await page.waitForTimeout(700)
await at(...(await legActionRow('reverse'))); await page.waitForTimeout(900)
const backAgain = (await savedLegs()).find(isConnecting)
if (backAgain?.wasShotBackwards) {
  fail('reversing the leg a second time did not put it back')
} else {
  pass('and turning it again puts it back, which is what makes it safe to try')
}

// ---- and a tap on a station's name is about the station ---------------------------------------
// The Android app has two station menus, and the table's is the one that offers to take you to the
// station on a drawing — which is the link between the two halves of the app. Scan the table, spot
// the reading that looks wrong, hold the station, and look at where it is. Held, because a tap on
// a station cell edits the reading like a tap anywhere else on the row.
const FROM_CELL_X = 25
const TO_CELL_X = 88

await longPress([TO_CELL_X, (await tableRow(0))[1]]); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-table-station.png') })
const farEndRows = await dialogTextRows()
// This station menu's only button is "Close", laid out where a lone confirmButton would be.
await at(...(await dialogConfirm())); await waitForDialogToClose()

await longPress([FROM_CELL_X, (await tableRow(0))[1]]); await page.waitForTimeout(700)
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

// *Show it on the plan*, which `context_station.xml` puts in `menu_navigate` near the foot of the
// menu rather than at the top. The origin's table menu is these five rows and only these: it is
// the active station by now so it cannot be made active, no leg made it so there is no
// `menu_leg`, nothing may delete it, and `ViewContext.TABLE` hides the jump to the table itself.
// The count above says the same thing a different way — six rows against nine, both counting the
// dialog's own Close — so if either is wrong both fail rather than one passing quietly.
const ORIGIN_TABLE_MENU = [
  'comment',
  'rename',
  'passage-size',
  'show-in-plan',
  'show-in-elevation',
]
await at(...(await stationMenuRow(ORIGIN_TABLE_MENU, 'show-in-plan')))
await page.waitForTimeout(1200)
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

/**
 * Did "unroll left" reach the whole passage below the station it was set on, and no further?
 *
 * The direction is not a property of one station: an extended elevation unrolls the cave onto a
 * line, so at a junction the surveyor is saying which way *everything beyond here* is drawn.
 * `SurveyUpdater.setExtendedElevationDirection` walks the subtree for that reason, and the dialog
 * used to assign the field on the one station instead — which this check could not see, because it
 * only ever asked about the station it had just edited. It passed for weeks over a drawing that
 * was wrong.
 *
 * The subtree is computed from the saved file rather than assumed, because which station the field
 * bar's chip is pointing at depends on every check before this one.
 */
const eeDirectionCarriedDownThePassage = (stations, from) => {
  const byName = new Map(stations.map((st) => [st.name, st]))
  const below = new Set()
  const walk = (name) => {
    for (const leg of byName.get(name)?.legs ?? []) {
      if (isSplay(leg) || !leg.destination || below.has(leg.destination)) continue
      below.add(leg.destination)
      walk(leg.destination)
    }
  }
  walk(from)
  // Nothing below it is no evidence either way, so say so rather than passing vacuously.
  if (below.size === 0) return false
  for (const st of stations) {
    const wanted = st.name === from || below.has(st.name) ? 'left' : 'right'
    if (st.eeDirection !== wanted) return false
  }
  return true
}

/**
 * The "From <station> · N stations" text on the field bar, found by where it actually is rather
 * than a fixed pixel: this is one of the things this file had hard-coded a tap onto that a
 * legitimate layout change moved out from under. `Simulate` used to sit between `Add reading` and this text
 * before finding 87 put it behind Developer Mode; the text slid left when the button did, and a
 * click aimed where it used to be landed on empty field bar instead.
 *
 * The text is drawn in the app's own pure-black `legend` colour - not `Add reading`'s Material
 * purple, and not the pale grid lines above it - so a scan for near-black pixels on the field
 * bar's own row finds it without needing to know what else is sharing that row this week.
 */
const fieldStatusChipSpot = async () => {
  const b64 = (await page.screenshot({ clip: box })).toString('base64')
  return page.evaluate(async (data) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    // The field bar's bottom row, from field-station.png: the chip sits at y=790 whatever else is
    // on the row with it, so the band is narrow enough to stay off the toolbar below and the
    // reading fields above.
    const top = 780
    const bottom = 800
    let minX = Infinity
    let maxX = -Infinity
    let y = top
    for (let row = top; row < bottom; row++) {
      for (let x = 0; x < c.width; x++) {
        const i = (row * c.width + x) * 4
        if (px[i] < 40 && px[i + 1] < 40 && px[i + 2] < 40) {
          minX = Math.min(minX, x)
          maxX = Math.max(maxX, x)
          y = row
        }
      }
    }
    if (!isFinite(minX)) return null
    return [Math.round((minX + maxX) / 2), y]
  }, b64)
}

// ---- a station can be named, and told what is there ----------------------------------------
// Numbered stations are fine down a straight passage and useless at a junction: the surveyor's
// notebook says "sump", and a survey where that name exists only on paper cannot be tied to the
// next trip's. The comment is the same argument — a lead nobody wrote down is a lead nobody goes
// back for.
const statusChip = await fieldStatusChipSpot()
if (!statusChip) fail('the field bar\'s status text was not found, so the station could not be named')
await at(...statusChip); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-station.png') })
await retype(await nodeFor('#station-name'), 'Sump')
await at(...(await dialogConfirm())); await waitForDialogToClose()

// The comment and the passage measurements are on the station's own menu, reached from the table
// rather than from the drawing: the row is in the same place whatever the plan is scrolled to, and
// the From cell of the first row is the station this has just renamed.
await at(...TABLE_TAB); await page.waitForTimeout(900)
await longPress([FROM_CELL_X, (await tableRow(0))[1]]); await page.waitForTimeout(700)
await at(...(await stationAction('comment'))); await page.waitForTimeout(700)
await at(...(await nodeFor('#station-comment'))); await page.waitForTimeout(250)
await page.keyboard.type('Continues, too tight for me', { delay: 15 })
await at(...(await dialogConfirm())); await waitForDialogToClose()

// Passage size from a tape rather than an instrument: two numbers become two splays, square to
// the passage, and a cross-section can then be drawn from a hand-booked survey.
await longPress([FROM_CELL_X, (await tableRow(0))[1]]); await page.waitForTimeout(700)
await at(...(await stationAction('passage-size'))); await page.waitForTimeout(700)
await retype(await nodeFor('#station-passage-left'), '1.5')
await retype(await nodeFor('#station-passage-right'), '2')
await page.screenshot({ path: join(shotDir, 'field-station-named.png') })
await at(...(await dialogConfirm())); await waitForDialogToClose()

// And which way the passage unrolls, which `menu_elevation` puts on the station's menu in the
// extended elevation and nowhere else — so this is the one that has to be done over there.
await at(...ELEVATION_TAB); await page.waitForTimeout(900)
const onElevation = (await stationSpots(sketchTop, sketchBottom)).active
if (!onElevation) {
  fail('the active station is not drawn on the extended elevation, so its direction cannot be set')
} else {
  await longPress(onElevation)
  await at(...(await stationAction('draw-left'))); await page.waitForTimeout(800)
}
await at(...PLAN_TAB); await page.waitForTimeout(700)

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
} else if (!eeDirectionCarriedDownThePassage(named, 'Sump')) {
  fail(
    'setting a station to unroll left did not carry down the passage beyond it: ' +
      named.map((st) => `${st.name}=${st.eeDirection}`).join(' '),
  )
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
await openSymbolStrip()
await at(...stripSquare('label')); await page.waitForTimeout(400)
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

// Back to drawing, so nothing after this places a label by accident — and the strip shut, so the
// drawing gets its forty pixels back before anything below measures a coordinate on it.
await closeSymbolStrip()
await at(...toolCell(1)); await page.waitForTimeout(400)

// ---- and the symbols that are not words either ----------------------------------------------
// The nineteen UIS symbols, taken from the app's own vector drawables and drawn through a path
// parser in commonMain. A stamped symbol has to carry the Therion name the canvas looks its
// artwork up by; if those two ever disagreed every symbol would silently draw as a fallback dot.
await openSymbolStrip()
await page.screenshot({ path: join(shotDir, 'field-symbol-palette.png') })
await at(...stripSquare('blocks')); await page.waitForTimeout(700)
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
await openSymbolStrip()
// Sixteen squares along, which is off the right-hand edge of every phone this file runs at.
await scrollSymbolStripToTheEnd()
await at(...scrolledStripSquare('water-flow')); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-symbol-strip-scrolled.png') })
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

// Back to drawing, so nothing after this stamps by accident, and the strip shut again.
await closeSymbolStrip()
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

// The tolerances are `preferences_instruments.xml`, which is a screen of its own — one of the five
// `preferences_main.xml` lists, and not the one the buzz or the sketch gestures are on. Three
// dialogs where this used to be one, which is three trips through the menu and is what the app
// itself asks of a surveyor.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('instruments'))); await page.waitForTimeout(800)
await retype(await numberField(0), '0.5')
await retype(await numberField(1), '12')
await page.screenshot({ path: join(shotDir, 'field-instrument-settings.png') })
await at(...(await settingsSave())); await page.waitForTimeout(700)

// `pref_vibrate_on_new_station`, which is on *General*: how a surveyor with the phone in a pocket
// learns the leg went in. By name rather than by looking for a switch: `Toggle` greys the whole
// row out on a device that cannot vibrate, and a greyed switch is not something the search for a
// switch-shaped run of primary colour can find.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('general'))); await page.waitForTimeout(800)
await at(...(await settingRow('settings_new_station_vibration_title'))); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-general-settings.png') })
await at(...(await settingsSave())); await page.waitForTimeout(700)

// And the two ways of moving the drawing without changing tool, which are `pref_hot_corners` and
// `pref_two_finger_movement` on *Sketching*. Both flipped from their defaults, so the file that
// comes out says the screen was actually read rather than that the defaults happened to be
// written.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('sketching'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_HOT_CORNERS))); await page.waitForTimeout(300)
await at(...(await settingsSwitch(SWITCH_TWO_FINGER))); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-sketching-settings-switches.png') })
await at(...(await settingsSave())); await page.waitForTimeout(700)

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
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('sketching'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_HOT_CORNERS))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(700)

// ---- chasing a lost instrument -------------------------------------------------------------
// `pref_auto_reconnect`. A cave breaks Bluetooth constantly — the surveyor walks round a corner
// with the phone, the instrument sleeps, a cold battery sags — and every one of those costs a trip
// to the connection screen with cold hands unless the app chases it. Off by default, as on
// Android, which is why it has to be reachable: a setting nobody can find is a setting nobody has.
//
// `ReconnectionPolicy` decides *whether* to chase and `ReconnectionTest` drives a fake radio
// through a drop and a recovery. What neither can do is show that the switch on the screen is
// wired to them, which is this.
//
// The row is below the fold now that the dialog is taller than an eight-hundred-pixel window, so
// it has to be scrolled to — which is itself worth checking, because a setting that exists only
// off the bottom of a dialog that does not scroll is a setting nobody can reach.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('instruments'))); await page.waitForTimeout(800)
await scrollSettingsToTheEnd()
await page.screenshot({ path: join(shotDir, 'field-settings-reconnect.png') })

await at(...(await chaseSwitch())); await page.waitForTimeout(400)
await page.screenshot({ path: join(shotDir, 'field-settings-reconnect-on.png') })
await at(...(await settingsSave())); await page.waitForTimeout(800)

const savedReconnect = await page.evaluate(() =>
  localStorage.getItem('sexytopo:f:preferences.txt'),
)
if (!savedReconnect || !savedReconnect.includes('autoReconnect=true')) {
  fail(`chasing a lost instrument was not turned on (${JSON.stringify(savedReconnect)})`)
} else {
  pass('a lost instrument can be told to be chased, and the setting is written down')
}

// Back off, because the rest of this file is written for the app's own defaults.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('instruments'))); await page.waitForTimeout(800)
await scrollSettingsToTheEnd()
await at(...(await chaseSwitch())); await page.waitForTimeout(400)
await at(...(await settingsSave())); await page.waitForTimeout(700)

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

await flipSketchingSwitch(SWITCH_HIGHLIGHT_LATEST_LEG)
const magentaOff = await magentaPixels()
if (magentaOff !== 0) {
  fail(`turning the mark off left ${magentaOff} magenta pixels on the plan`)
} else {
  pass('and it can be turned off, for a surveyor who would rather it were not there')
}
await flipSketchingSwitch(SWITCH_HIGHLIGHT_LATEST_LEG)

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

// ---- the drawing can be made bigger ---------------------------------------------------------
// `preferences_sketching.xml`'s numeric group, which this port hard-coded. A plan read on a desk
// and a plan read at arm's length under a helmet, through a scratched screen, by a light pointed
// at the rock, are not the same picture — and the surveyor who needs a heavier line needs it at
// the station rather than at home.
//
// `DrawingSizeTest` renders the same survey at two leg widths through headless Skia and counts the
// red, which is the rendering half. This is the half only the running app can show: that the
// number typed into the box on the screen is the number the canvas draws with. The two together
// are the connection that findings 48, 49 and 50 were all about — a value that round-trips
// perfectly and that nothing on the way to the screen ever reads.
const thinCentreline = await centrelinePixels()

await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('sketching'))); await page.waitForTimeout(800)
// The boxes are below the five switches now, so the dialog has to be wound down to them.
await scrollSettingsToTheEnd()
await page.screenshot({ path: join(shotDir, 'field-sketching-settings.png') })
await retype(await sketchField('leg-width'), '8')
await at(...(await settingsSave())); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-fat-centreline.png') })

const fatCentreline = await centrelinePixels()
const savedStyle = await page.evaluate(() => localStorage.getItem('sexytopo:f:preferences.txt'))
if (!(fatCentreline > thinCentreline * 1.8)) {
  fail(
    `asking for an 8dp centreline drew ${fatCentreline} red pixels against ${thinCentreline}, ` +
      'so the setting reaches the file and not the page',
  )
} else if (!savedStyle || !savedStyle.includes('legWidthDp=8')) {
  fail(`the drawing sizes were not written down (${JSON.stringify(savedStyle)})`)
} else {
  pass(
    `the centreline can be made heavier for a head torch (${thinCentreline} to ${fatCentreline})`,
  )
}

// Back to the app's own width, because every check below reads this plan.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('sketching'))); await page.waitForTimeout(800)
await scrollSettingsToTheEnd()
await retype(await sketchField('leg-width'), '2')
await at(...(await settingsSave())); await page.waitForTimeout(800)

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
// Five of the eleven were session-only until the menu was split: a surveyor who turned the splays
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
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('trip'))); await page.waitForTimeout(800)
await at(...TRIP_ADD_NAME); await page.waitForTimeout(250)
await page.keyboard.type('L. Waterworth', { delay: 15 })
await at(...TRIP_ADD_BUTTON); await page.waitForTimeout(600)
await at(...TRIP_ROLE_BOOK); await page.waitForTimeout(300)
await at(...TRIP_INSTRUMENT); await page.waitForTimeout(250)
await page.keyboard.type('DistoX2', { delay: 15 })
await page.screenshot({ path: join(shotDir, 'field-trip.png') })

// Save is gated on an actual licence choice, not just on there being something to save:
// everything else needed to save a trip is already on screen at this point, so this is the one
// place a click on Save tests the licence gate alone. A trip written here would mean the gate
// was never wired up at all.
await at(...(await dialogConfirm())); await page.waitForTimeout(500)
const tripBeforeALicenceIsChosen = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? JSON.parse(localStorage.getItem(key)).trip ?? null : null
})
if (tripBeforeALicenceIsChosen) {
  fail('Save wrote a trip before a licence was chosen for it')
} else {
  pass('Save does nothing until a licence has been chosen, even with everything else filled in')
}

const licenceBox = await tripLicenceField()
if (licenceBox === null) {
  fail('the licence box is not marked as unanswered, so there is nothing to say Save waits for')
} else {
  await at(...licenceBox); await page.waitForTimeout(250)
  await page.keyboard.type('CC0', { delay: 15 })
}
await at(...(await dialogConfirm())); await page.waitForTimeout(800)

const trip = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? JSON.parse(localStorage.getItem(key)).trip ?? null : null
})
if (!trip) {
  fail('the trip details were not saved with the survey')
} else if (!JSON.stringify(trip).includes('L. Waterworth')) {
  fail(`the team did not reach the saved survey (${JSON.stringify(trip).slice(0, 120)})`)
} else if (!JSON.stringify(trip).includes('CC0')) {
  fail(`the chosen licence did not reach the saved survey (${JSON.stringify(trip).slice(0, 120)})`)
} else {
  pass('a trip records who was there, with what, on what date, and under what licence')
}

// Reopening a trip that already carries a licence counts the question as already answered - a
// surveyor fixing a typo in the instrument field should not have to re-pick a licence they
// already chose.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('trip'))); await page.waitForTimeout(800)
await at(...(await dialogConfirm())); await page.waitForTimeout(800)
const tripAfterReopening = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  return key ? JSON.parse(localStorage.getItem(key)).trip ?? null : null
})
if (!tripAfterReopening || !JSON.stringify(tripAfterReopening).includes('CC0')) {
  fail('reopening a trip that already had a licence should not have needed it re-chosen')
} else {
  pass('a trip that already has a licence does not have to have it chosen again')
}

// ---- and the survey can leave the phone as a file ------------------------------------------
// The clipboard reaches an email. Only a file reaches Therion, and a survey that cannot get into
// Therion is a weekend of somebody's life spent producing something they then have to type up
// again from a photograph of a screen.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await page.screenshot({ path: join(shotDir, 'field-menu.png') })
await at(...(await menuRow('export'))); await page.waitForTimeout(900)
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

// ---- and this app's own format exports the whole survey ---------------------------------------
// A SexyTopo survey is `Name.data.json` *and* its two sketches. Exporting the data file alone
// hands somebody a centreline and keeps the drawing — which is precisely the loss the importer had
// at the other end, where it read the data file and never looked for the sketches beside it.
// Fixing one end and not the other would leave this app able to read a complete survey and unable
// to write one, which is the worse half: the reader's failure loses somebody else's work, the
// writer's loses your own.
//
// So: one press of Save, and count what comes out.
await at(...(await exportChip('json'))); await page.waitForTimeout(700)

const nativeFiles = []
const collect = (download) => nativeFiles.push(download.suggestedFilename())
page.on('download', collect)
await at(...(await exportSaveFile()))
await page.waitForTimeout(3000)
page.off('download', collect)

const wanted = ['Swildons.data.json', 'Swildons.plan.json', 'Swildons.ext-elevation.json']
const missing = wanted.filter((name) => !nativeFiles.includes(name))
if (missing.length > 0) {
  fail(
    `the native export wrote ${nativeFiles.length} file(s) — ${nativeFiles.join(', ') || 'none'}` +
      ` — and is missing ${missing.join(', ')}, so the drawing does not leave the phone`,
  )
} else {
  pass(`this app's own format exports the whole survey (${nativeFiles.length} files)`)
}

// ---- and it goes as one file, which is what handing it to somebody means ----------------------
// Three files is what the *format* is; it is not what a person can send. A surveyor at the end of a
// trip has one thing to do with a survey — give it to whoever is drawing up — and three downloads
// that have to arrive together, keep their names and be dropped into the same folder is a way to
// lose a drawing. The Android app has had `SurveyZipSharer` since 2018; this port had only the
// receiving half, so it could read a survey somebody else had zipped and could not make one.
//
// Checked by unzipping it with a tool that knows nothing about this app. A zip written by hand —
// which this one is, there being no zip writer in Kotlin's common library — is exactly the kind of
// file that reads back correctly in the code that wrote it and nowhere else.
await page.screenshot({ path: join(shotDir, 'field-export-share.png') })
const shared = await Promise.all([
  page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
  at(...(await exportShareButton())),
]).then(([d]) => d)

if (shared === null) {
  fail('Share survey produced no file at all')
} else if (shared.suggestedFilename() !== 'Swildons.zip') {
  fail(`the shared survey came out named ${shared.suggestedFilename()}, not Swildons.zip`)
} else {
  const zip = await shared.path()
  const listed = spawnSync('unzip', ['-Z1', zip], { encoding: 'utf8' })
  const names = (listed.stdout ?? '').split('\n').map((n) => n.trim()).filter(Boolean)
  const wantedInZip = ['Swildons.data.json', 'Swildons.plan.json', 'Swildons.ext-elevation.json']
  const absent = wantedInZip.filter((name) => !names.includes(name))
  if (listed.status !== 0) {
    fail(`unzip would not read the shared survey: ${(listed.stderr ?? '').trim().slice(0, 200)}`)
  } else if (absent.length > 0) {
    fail(`the shared survey holds ${names.join(', ') || 'nothing'} and is missing ${absent.join(', ')}`)
  } else {
    // The names being right is not the same as the bytes being right: a central directory can
    // agree with itself and still point at rubbish. Read one entry out and require the survey.
    const data = spawnSync('unzip', ['-p', zip, 'Swildons.data.json'], { encoding: 'utf8' })
    const plan = spawnSync('unzip', ['-p', zip, 'Swildons.plan.json'], { encoding: 'utf8' })
    let survey = null
    let sketch = null
    try { survey = JSON.parse(data.stdout) } catch (e) { survey = null }
    try { sketch = JSON.parse(plan.stdout) } catch (e) { sketch = null }
    const legsIn = (s) => (s.stations ?? []).reduce((n, st) => n + (st.legs ?? []).length, 0)
    const marks = sketch === null
      ? 0
      : (sketch.paths ?? []).length + (sketch.labels ?? []).length + (sketch.symbols ?? []).length
    if (survey === null) {
      fail(`the survey inside the shared file is not JSON: ${(data.stdout ?? '').slice(0, 120)}`)
    } else if (survey.name !== 'Swildons') {
      fail(`the survey inside the shared file calls itself ${survey.name}, not Swildons`)
    } else if (legsIn(survey) === 0) {
      fail('the survey inside the shared file has no legs, so only the header went')
    } else if (sketch === null) {
      fail(`the drawing inside the shared file is not JSON: ${(plan.stdout ?? '').slice(0, 120)}`)
    } else if (marks === 0) {
      fail('the drawing inside the shared file is blank, so the sketch did not go with it')
    } else {
      pass(
        `a whole survey can be handed over as one file` +
          ` (${names.length} in Swildons.zip, ${legsIn(survey)} legs and` +
          ` ${marks} marks on the plan)`)
    }
  }
}

// ---- and the drawing that leaves the cave is the one the surveyor asked for ------------------
// The SVG is the only export that is a picture, and it is what everybody who was not on the trip
// sees. What it should contain depends entirely on where it is going: a drawing headed for Inkscape
// to be composed with three other trips wants no legend, no grid and a transparent page, because
// all of that gets added once at the end; a drawing headed for a club newsletter wants every bit
// of it. The exporter has taken all seventeen of those options since it was ported and every
// caller passed the defaults, so the app had the whole feature and offered none of it.
//
// The check is the one that catches a switch wired to nothing: export, turn one thing off, export
// again, and require the file to have changed in exactly that way.
await at(...(await exportChip('svg'))); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-export-svg.png') })

const svgAsShipped = await savedExport()
if (svgAsShipped === null) {
  fail('the drawing does not save as an SVG at all')
} else if (!svgAsShipped.includes('id="sketch"') || !svgAsShipped.includes('id="grid"')) {
  fail('a default SVG export is missing the sketch or the grid, so the check below cannot fail')
} else {
  pass('the drawing exports as an SVG with the sketch and the grid in it')
}

await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-export-svg-options.png') })
// The first switch in the dialog: *Draw the sketch*. Found rather than measured, like every other
// switch in this file — see switchRows().
await at(...(await settingsSwitch(0))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

const svgWithoutSketch = await savedExport()
if (svgWithoutSketch === null) {
  fail('the SVG would not save after its options were changed')
} else if (svgWithoutSketch.includes('id="sketch"')) {
  fail('turning the sketch off left it in the exported drawing — the switch reaches nothing')
} else if (!svgWithoutSketch.includes('id="centreline"')) {
  fail('turning the sketch off took the centreline with it, which is a different drawing')
} else {
  pass('an SVG export option reaches the file, and takes out only what it names')
}

// And it is remembered, so a surveyor who sets this up at home does not set it again at the
// entrance. The file rather than a reload, because the reload is checked further down and the
// value has to be *written* before it can be read back.
const savedOptions = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('preferences.txt'))
  return key === undefined ? null : localStorage.getItem(key)
})
if (savedOptions === null || !savedOptions.includes('svgShowSketch=false')) {
  fail('the SVG export options were not written to storage, so they last until the app closes')
} else {
  pass('the SVG export options are remembered between runs')
}

// Put it back, so the exports checked after this one are the drawing they were written for.
await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
await at(...(await settingsSwitch(0))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

// ---- and the Therion project is laid out the way the surveyor's other trips are ---------------
// The same shape as the SVG options and found in the same sweep: Th2Exporter.Options has carried
// seven of the ten `pref_therion_*` settings since the scrap exporter was ported, and every caller
// passed the defaults. These decide what a Therion project's files are called and what goes in
// them, which for a format whose files refer to each other by name is whether the project builds.
await at(...(await exportChip('th2'))); await page.waitForTimeout(700)

const th2WithSections = await savedExport()
if (th2WithSections === null) {
  fail('the scrap file does not save at all')
} else if (!th2WithSections.includes('scrap ')) {
  fail('the exported .th2 has no scrap in it, so the check below cannot fail')
} else {
  pass('the drawing exports as a Therion scrap file')
}

await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-export-therion-options.png') })
// The dialog's three switches are its last three rows: cross-sections, symbols, text. Wheeled to
// the end first, because ten settings do not fit on a phone.
await scrollSettingsToTheEnd()
await at(...(await settingsSwitch(-3))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

const th2WithoutSections = await savedExport()
if (th2WithoutSections === null) {
  fail('the scrap file would not save after its options were changed')
} else if (th2WithoutSections.length >= th2WithSections.length) {
  fail('turning the cross-sections off left the scrap file the same size — the switch does nothing')
} else if (!th2WithoutSections.includes('scrap ')) {
  fail('turning the cross-sections off took the passage scrap with it, which is a different file')
} else {
  pass('a Therion export option reaches the scrap file, and takes out only what it names')
}

// ---- and the stations can be left out, so they can live in a scrap of their own --------------
// `TherionExportOptions` carries four settings that are *not* among the ten `pref_therion_*`
// preferences: the Android app asks for them in a dialog on the way out of every export. The port
// had the ten and none of these four, so it always wrote one scrap with the stations in it.
//
// This checks the switch. The two scrap *counts* beside it are numbers rather than switches and
// are covered by the exporter's own tests and by the preference round-trip, not from here — said
// plainly rather than left to be assumed, because a browser check that quietly covers three of
// four settings reads exactly like one that covers all of them.
//
// Why a Therion surveyor wants it: with the stations in a scrap of their own, re-exporting after a
// correction to the centreline does not overwrite a drawing somebody has spent an evening on.
await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
await scrollSettingsToTheEnd()
const therionSwitches = (await switchRows()).length
if (therionSwitches !== 5) {
  fail(`the Therion options show ${therionSwitches} switches, not the five this check counts from`)
  await at(...(await settingsSave())); await page.waitForTimeout(900)
} else {
  // Fifth from the end: stations in the first plan scrap, then the elevation's, then the three
  // that were already here — cross-sections, symbols, text.
  await at(...(await settingsSwitch(-5))); await page.waitForTimeout(300)
  await at(...(await settingsSave())); await page.waitForTimeout(900)

  const th2WithoutStations = await savedExport()
  const stationsIn = (th2) => (th2.match(/point [^\n]* station /g) ?? []).length
  if (th2WithoutStations === null) {
    fail('the scrap file would not save after the stations were turned off')
  } else if (stationsIn(th2WithSections) === 0) {
    fail('the scrap file had no stations in it to begin with, so this check cannot fail')
  } else if (stationsIn(th2WithoutStations) !== 0) {
    fail(
      `the stations were written anyway ` +
        `(${stationsIn(th2WithSections)} before, ${stationsIn(th2WithoutStations)} after)`)
  } else if (!th2WithoutStations.includes('scrap ')) {
    fail('turning the stations off took the scrap with them, which is a different file')
  } else {
    pass(
      `the stations can be left out of the Therion scrap ` +
        `(${stationsIn(th2WithSections)} of them, and the scrap is still there)`)
  }

  // Back on, for the same reason as everything else here.
  await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
  await scrollSettingsToTheEnd()
  await at(...(await settingsSwitch(-5))); await page.waitForTimeout(300)
  await at(...(await settingsSave())); await page.waitForTimeout(900)
}

// Put it back, for the same reason as above.
await at(...(await exportOptionsButton())); await page.waitForTimeout(800)
await scrollSettingsToTheEnd()
await at(...(await settingsSwitch(-3))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

await at(...PLAN_TAB); await page.waitForTimeout(600)

// ---- a leg shot from the far end goes in the right way round -------------------------------
// Backsight mode is how a passage gets surveyed on the way back out, and it is the one setting
// that can be wrong without the numbers showing it: the readings look perfectly ordinary and the
// cave comes out pointing the other way. The stored leg has to carry the flag, and the table has
// to show the reading the way the surveyor took it.
await reading(4.0, 300, 5, { mode: 1 })
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

// ---- Splays Only keeps a run of splays a run of splays -------------------------------------
// `action_input_mode_cal_check`, which `strings.xml` calls **Splays Only**. This port left it out
// for a long time on the reading that it exists to check an instrument against a baseline and is
// therefore useless without one. What it actually does is stop *anything* promoting to a station,
// which is what a surveyor wants taking a run of splays round a chamber: three that happen to
// agree would otherwise plant a station in the middle of the floor.
//
// So the check is the one that would catch the mode doing nothing: three readings that agree
// within tolerance — which in any other mode is exactly the recipe for a station — and no new
// station at the end of it.
const stationsBefore = new Set((await savedLegs()).filter(isConnecting).map((l) => l.destination))
await reading(7.5, 210, 0, { mode: 3 })
await reading(7.51, 210.2, 0.1)
await reading(7.49, 209.8, -0.1)
await page.screenshot({ path: join(shotDir, 'field-splays-only.png') })

const afterSplaysOnly = (await savedLegs()).filter(isConnecting).map((l) => l.destination)
const grew = afterSplaysOnly.filter((d) => !stationsBefore.has(d))
const splaysNow = (await savedLegs()).filter(isSplay).length
if (grew.length > 0) {
  fail(`Splays Only promoted three agreeing readings to station ${grew.join(', ')}`)
} else if (splaysNow < 3) {
  fail(`Splays Only kept only ${splaysNow} splays, so the readings went nowhere at all`)
} else {
  pass(`Splays Only keeps three agreeing readings as splays (${splaysNow} splays, no new station)`)
}

// ---- the passage can be booked with the leg, at the station you are standing at ---------------
// `pref_lrud_fields`. Four tape measurements beside the reading, which for a compass-and-tape
// survey is the whole station in one dialog instead of going back to one you have already left.
//
// The check is about *which station they land on*, because that is the way this goes wrong
// silently. A reading that promotes moves the active station to the far end of the shot, so a
// passage size attached after the leg lands on the station just created — putting the walls of
// this chamber around the next one. Nothing in the numbers afterwards says so: they are ordinary
// splays either way, on a station that exists, at a bearing that really was measured.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(900)
await at(...(await settingsSwitch(SWITCH_BOOK_PASSAGE_SIZE))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

/** Every station in the saved survey, by name, with how many splays hang off it. */
const splayCounts = () => page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('Swildons.data.json'))
  if (!key) return {}
  const out = {}
  for (const st of JSON.parse(localStorage.getItem(key)).stations ?? []) {
    out[st.name] = (st.legs ?? []).filter((l) => l.destination === '-').length
  }
  return out
})

const wallsBefore = await splayCounts()
// Forward, explicitly. The check above leaves the app in **Splays Only**, where nothing promotes
// at all — so without this the three readings below stay three splays, no station is created, and
// the from-station rule this check exists for is never exercised. It failed exactly that way
// first time, which is the check doing its job: it counted seven splays where it wanted four and
// said so, rather than passing on a survey that had never promoted.
//
// The first two readings do not promote, so the third is the one that moves the active station —
// which makes it the only one where getting the from-station wrong is visible.
await reading(6.0, 300, 0, { mode: 0 })
await reading(6.01, 300.2, 0.1)
await reading(5.99, 299.8, -0.1, { lrud: ['1.2', '2.3', '3.4', '0.6'] })
await page.screenshot({ path: join(shotDir, 'field-lrud-with-reading.png') })

const wallsAfter = await splayCounts()
const gained = Object.keys(wallsAfter)
  .filter((name) => (wallsAfter[name] ?? 0) - (wallsBefore[name] ?? 0) === 4)
const fresh = Object.keys(wallsAfter).filter((name) => !(name in wallsBefore))
if (gained.length !== 1) {
  fail(
    `the passage size did not become four splays on one station ` +
      `(before ${JSON.stringify(wallsBefore)}, after ${JSON.stringify(wallsAfter)})`,
  )
} else if (fresh.length !== 1) {
  fail(`the third reading did not make a station, so this check proves nothing (${fresh.join(', ')})`)
} else if (gained[0] === fresh[0]) {
  fail(
    `the passage size landed on ${fresh[0]}, the station the reading created — it was measured ` +
      `at the one the surveyor was standing at`,
  )
} else {
  pass(`passage size is booked with the reading, at the station you are standing at (${gained[0]})`)
}

// Put the input mode back, and not as tidying-up: the check above this one sets **Splays Only**
// and a check *below* asserts that it survived being written to the preferences file. Selecting
// Forward here to make the readings promote therefore broke that one — which it duly reported,
// two checks later, as "Splays Only did not reach the preferences file". A chip tap and a Cancel
// sets the mode without recording anything, because `chooseInputMode` persists on the tap.
await at(...ADD_READING); await page.waitForTimeout(700)
await at(...(await modeChip(3))); await page.waitForTimeout(300)
await at(...(await cardButton(CARD_CANCEL_X))); await page.waitForTimeout(600)

// Put it back, so the dialogs checked after this one are the ones they were written for.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(900)
await at(...(await settingsSwitch(SWITCH_BOOK_PASSAGE_SIZE))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(900)

// ---- the manual entry button can be put away --------------------------------------------------
// `pref_manual_controls`. On the Android table view it hides two floating buttons; here it is the
// *Add reading* button on the field bar, which is the same control. Worth having once an
// instrument is talking, for the room it gives back on a phone.
//
// Checked by the ink in the field bar rather than by looking for a word: the bar is drawn to a
// canvas, and a button that has gone takes a measurable amount of dark pixels with it.
const fieldBarInk = async () => {
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
    // The strip holding the two buttons, above the sketch toolbar.
    let ink = 0
    for (let y = c.height - 130; y < c.height - 95; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        // The buttons are Material 3 filled: a solid block of primary against the panel grey.
        if (px[i + 2] > px[i] + 25 && px[i + 2] > 120) ink++
      }
    }
    return ink
  }, [b64])
}

const withManualButton = await fieldBarInk()

await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_MANUAL_ENTRY))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-no-manual-entry.png') })

const withoutManualButton = await fieldBarInk()
const savedManual = await page.evaluate(() =>
  localStorage.getItem('sexytopo:f:preferences.txt'),
)
if (!(withManualButton > 400)) {
  fail(`the field bar's buttons were not found to begin with (${withManualButton})`)
} else if (!(withoutManualButton < withManualButton * 0.7)) {
  fail(
    `turning the manual controls off left the button on screen ` +
      `(${withManualButton} then ${withoutManualButton})`,
  )
} else if (!savedManual || !savedManual.includes('manualControls=false')) {
  fail(`the manual-controls preference was not written down (${JSON.stringify(savedManual)})`)
} else {
  pass(
    `the manual entry button can be put away (${withManualButton} to ${withoutManualButton})`,
  )
}

// Back on, because the checks below type readings.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_MANUAL_ENTRY))); await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(800)

// ---- a bearing can be typed the way a compass reads it ---------------------------------------
// `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`. A DistoX reports a decimal and nobody needs
// this; a sighting compass is graduated in minutes, reads 123° 30′, and converting that in your
// head at every station is how a survey acquires arithmetic errors nobody can find afterwards.
// This port went out of its way to support a compass and tape — loosened tolerances, manual entry
// — and then asked for a decimal nobody's instrument shows.
//
// `DegreesMinutesSecondsTest` has the conversion, including the one upstream gets wrong (finding
// 54). This is the half only a running app can show: that the switch changes the card, and that
// what is typed into the three boxes reaches the survey as one angle.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_BEARINGS_IN_MINUTES))); await page.waitForTimeout(300)
await at(...(await settingsSwitch(SWITCH_INCLINATIONS_IN_MINUTES)))
await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(800)

await at(...ADD_READING); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-dms-card.png') })

// 123 degrees 30 minutes, and five and a half degrees *down* — typed as the degrees box's sign,
// which is the +/- button, because no mobile numeric keypad has a minus key.
const splaysBeforeDms = (await savedLegs()).filter(isSplay).length
await retype(await onCard(CARD_DISTANCE), '5')
await retype(await onCard(DMS_AZIMUTH_DEGREES), '123')
await retype(await onCard(DMS_AZIMUTH_MINUTES), '30')
await retype(await onCard(DMS_INCLINATION_DEGREES), '5')
await retype(await onCard(DMS_INCLINATION_MINUTES), '30')
await at(...(await onCard(DMS_SIGN_BUTTON))); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-dms-typed.png') })
await at(...(await cardButton(CARD_ADD_SPLAY_X))); await page.waitForTimeout(900)

const dmsSplays = (await savedLegs()).filter(isSplay)
const typedInMinutes = dmsSplays.find((l) => Math.abs(l.azimuth - 123.5) < 0.01)
if (dmsSplays.length !== splaysBeforeDms + 1) {
  fail(`typing an angle in minutes did not make a reading (${splaysBeforeDms} then ${dmsSplays.length})`)
} else if (!typedInMinutes) {
  fail(
    '123 degrees 30 minutes did not reach the survey as 123.5: ' +
      dmsSplays.map((l) => l.azimuth).join(', '),
  )
} else if (!(Math.abs(typedInMinutes.inclination + 5.5) < 0.01)) {
  // The direction, not just the magnitude: minutes are a magnitude and the degrees box carries
  // the sign, so a port that added them without looking would store +5.5 for a downward shot.
  fail(`the shot went ${typedInMinutes.inclination} degrees rather than -5.5`)
} else {
  pass('a bearing can be typed the way a compass reads it, minutes and all')
}

// Back to decimal, because every check below reads the ordinary card.
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('manual-entry'))); await page.waitForTimeout(800)
await at(...(await settingsSwitch(SWITCH_BEARINGS_IN_MINUTES))); await page.waitForTimeout(300)
await at(...(await settingsSwitch(SWITCH_INCLINATIONS_IN_MINUTES)))
await page.waitForTimeout(300)
await at(...(await settingsSave())); await page.waitForTimeout(800)


// ---- and the mode the surveyor chose is written down ----------------------------------------
// `SurveyManager.getInputMode` reads this out of `generalPrefs` on the Android app's way in. Here
// it was a plain `var` that started at FORWARD every run, so a surveyor working back down a
// passage on backsights, whose phone was killed in a pocket between stations, came back to
// foresights — and the field bar only says anything when the mode is *not* FORWARD, so the state
// it came back in is the one that looks normal. Every leg after that is turned end for end and
// there is nothing in the numbers to show it happened.
//
// This half is the one no unit test can do: that a real tap on a real chip reaches the file. The
// other half — that the file reaches the app on the way back in — is `AppPreferencesTest`, which
// closes and reopens a `DemoState` over one store.
const modeInStore = async () =>
  ((await page.evaluate(() => localStorage.getItem('sexytopo:f:preferences.txt'))) || '')
    .split('\n')
    .find((line) => line.startsWith('inputMode=')) ?? '(nothing)'

const modeSaved = await modeInStore()

// Back to forward, so nothing after this inherits it.
await at(...ADD_READING); await page.waitForTimeout(600)
await at(...(await modeChip(0))); await page.waitForTimeout(300)
await page.screenshot({ path: join(shotDir, 'field-input-mode.png') })
await at(...(await cardButton(CARD_CANCEL_X))); await page.waitForTimeout(500)

// Asserted both ways round: a file that said CALIBRATION_CHECK and went on saying it would be a
// value written once and then stuck, which is its own bug and would pass a one-sided check.
const modeBack = await modeInStore()
if (modeSaved !== 'inputMode=CALIBRATION_CHECK') {
  fail(`Splays Only did not reach the preferences file (${modeSaved})`)
} else if (modeBack !== 'inputMode=FORWARD') {
  fail(`going back to foresights did not reach the preferences file (${modeBack})`)
} else {
  pass('the input mode is written down, so a backsight run survives the app being killed')
}

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
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await savedSurveyRow('delete', 'Swildons'))); await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-confirm-delete-survey.png') })

// A Cancel that missed its button would leave the dialog up and also leave the survey intact, so
// this check on its own could pass for the wrong reason. What gives it teeth is the real delete
// below: that only works from a dismissed dialog, so if Cancel did nothing, the next check fails.
const beforeCancel = await savedLegs()
const deleteButtons = await dialogButtons()
if (deleteButtons.length !== 2) {
  fail(`the delete confirmation offered ${deleteButtons.length} buttons, not Cancel and Delete`)
}
await at(deleteButtons[0], (await dialogTextRows()).pop()); await page.waitForTimeout(700)
if ((await savedLegs()).length !== beforeCancel.length) {
  fail('cancelling the delete removed the survey anyway')
} else {
  pass('a delete can be called off')
}

await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await savedSurveyRow('delete', 'Swildons'))); await page.waitForTimeout(700)
await at(...(await dialogConfirm())); await page.waitForTimeout(900)

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
await at(...(await overflowButton())); await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'field-import-menu.png') })
await at(...(await menuRow('import-file'))); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-import-dialog.png') })

// The survey's drawing, put beside it in the app's own storage, which is exactly where the four
// files of a survey land when somebody AirDrops one or unzips it into the Files app. The chooser
// takes one file; the importer has to notice the rest.
await page.evaluate((plan) => {
  localStorage.setItem('sexytopo:f:Eastwater.plan.json', plan)
}, EXAMPLE_PLAN_JSON)

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

  // And it brought the drawing with it. The fixture above had no sketch until now, which is why
  // this check passed for months while the importer dropped every drawing it was handed: the
  // centreline arrived, the survey appeared in the library, and the hours of work did not.
  const planPaths = await page.evaluate(() => {
    const key = Object.keys(localStorage).find((k) => k.endsWith('Eastwater/Eastwater.plan.json'))
    if (!key) return null
    return (JSON.parse(localStorage.getItem(key)).paths ?? []).length
  })
  if (planPaths === null) {
    fail('the imported survey was saved with no plan sketch file at all')
  } else if (planPaths < 1) {
    fail('the survey came in without its drawing: the plan sketch is empty')
  } else {
    pass(`and the drawing came with it (${planPaths} path in the plan)`)
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
await at(...(await overflowButton())); await page.waitForTimeout(600)
// One saved survey now: the Eastwater just imported.
await at(...(await menuRow('import-file'))); await page.waitForTimeout(1000)
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
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('import-file'))); await page.waitForTimeout(900)

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
// the names, the licence, and every character being one the bundled font can draw. What is only
// checkable here
// is that the box opens and that Material has not clipped it to nothing: it is a screenful and a
// half of text, and a Compose dialog that does not fit is cut off from the bottom, which is where
// the licence is.
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('about'))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-about.png') })

const aboutHeight = await dialogHeight()
if (aboutHeight === null) {
  fail('the About box did not open, so the licence is nowhere in the app')
} else if (aboutHeight < box.height * 0.4) {
  fail(`the About box is only ${aboutHeight}px tall, which is not the text it should hold`)
} else {
  pass('the app says who wrote it and under what licence')
}
await at(...(await dialogConfirm())); await waitForDialogToClose()

// ---- the manual ------------------------------------------------------------------------------
// `GuideActivity` shows a 23 KB HTML guide in a WebView. There is no WebView here: the file is
// bundled verbatim and read into Compose by `parseManual`, whose *content* is checked off the
// shipped file by `ManualContentTest` — every tag drawn, every heading, paragraph and list item
// counted against the file's own tags, every link pointing at a section that exists.
//
// What only a running app can say is that it is on the screen and behaves like a document: that a
// page of text is drawn rather than a blank surface, that it scrolls, that tapping the contents
// list moves you, and that Close gives the cave back. Each is measured off the pixels, because
// "the view was composed" is not the same claim as "there is a manual to read".

/** How many rows of the screen have text on them, and roughly how much on each. */
const inkProfile = async () => {
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
    const rows = []
    let inked = 0
    for (let y = 0; y < c.height; y++) {
      let count = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        // Dark and grey: the manual's body text, and nothing the survey canvas draws in quantity.
        if (px[i] < 120 && Math.abs(px[i] - px[i + 1]) < 30 && Math.abs(px[i] - px[i + 2]) < 30) {
          count++
        }
      }
      rows.push(count)
      if (count > 2) inked++
    }
    return { inked, rows }
  }, [b64])
}

/**
 * How much of the screen is the app's own panel green.
 *
 * The discriminator between the manual and the survey, and the ink count is not: the manual inks
 * 226 rows of a 900-pixel screen and the survey behind it inks 237, because a page of text with
 * its line spacing and a cave drawing with a toolbar come to much the same thing. That near-miss
 * is why this helper exists — the first version of these checks used the ink and could not tell
 * the two screens apart. The green is not ambiguous: the app bar and the sketch panel are tens of
 * thousands of pixels of it and a full-screen document has none. Measured: 41,195 against 0.
 */
const panelGreen = async () => {
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
    let count = 0
    for (let i = 0; i < px.length; i += 4) {
      if (
        Math.abs(px[i] - panel[0]) < 14 &&
        Math.abs(px[i + 1] - panel[1]) < 14 &&
        Math.abs(px[i + 2] - panel[2]) < 14
      ) {
        count++
      }
    }
    return count
  }, [b64, SKETCH_PANEL])
}

/** How different two of those are, as a fraction of the rows. */
const profileChange = (before, after) => {
  let moved = 0
  for (let i = 0; i < before.rows.length; i++) {
    if (Math.abs(before.rows[i] - after.rows[i]) > 3) moved++
  }
  return moved / before.rows.length
}

await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('manual')))

// Waited for rather than slept through. The manual is a 23 KB resource read off the bundle and
// parsed before anything is drawn, and a fixed delay long enough on this machine is a flake on a
// slower one.
let green = await panelGreen()
for (let tries = 0; tries < 20 && green > 1000; tries++) {
  await page.waitForTimeout(400)
  green = await panelGreen()
}
await page.screenshot({ path: join(shotDir, 'field-manual.png') })

const manualOpen = await inkProfile()
if (green > 1000) {
  fail(`the manual did not open: ${green} pixels of the app's panel green are still on screen`)
} else if (manualOpen.inked < 150) {
  fail(
    `the manual is only ${manualOpen.inked} rows of text on a ${box.height}-pixel screen, which ` +
      'is a blank page rather than a manual',
  )
} else {
  pass(`the manual opens and is a page of text (${manualOpen.inked} rows have writing on them)`)
}

// Scrolling. A document that does not scroll is a screenful of a 23 KB guide.
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
await page.mouse.wheel(0, 900)
await page.waitForTimeout(900)
const scrolled = await inkProfile()
if (profileChange(manualOpen, scrolled) < 0.3) {
  fail('the manual did not move when scrolled, so only its first screenful can be read')
} else {
  pass('the manual scrolls, so the whole guide can be read')
}

/**
 * The rows of the manual that are links, found by their colour rather than counted off a margin.
 *
 * Material draws every link on this screen in the primary colour and nothing else on it is that
 * colour — the body text is black on a pale surface — so the same blue-over-green signature that
 * finds a dialog's buttons finds the Close button and the thirteen contents rows here. Returned
 * as bands so a row's centre is its own midpoint, which survives the manual being restyled.
 */
const manualLinkRows = async () => {
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
    const bands = []
    let run = null
    for (let y = 0; y < c.height; y++) {
      let count = 0
      let sumX = 0
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        const [r, g, b] = [px[i], px[i + 1], px[i + 2]]
        if (b > r && r > g && b - g > 30 && b < 230) {
          count++
          sumX += x
        }
      }
      if (count > 4) {
        if (run === null) run = { top: y, bottom: y, pixels: count, sumX }
        else { run.bottom = y; run.pixels += count; run.sumX += sumX }
      } else if (run !== null) {
        bands.push({ y: Math.round((run.top + run.bottom) / 2), x: Math.round(run.sumX / run.pixels) })
        run = null
      }
    }
    if (run !== null) {
      bands.push({ y: Math.round((run.top + run.bottom) / 2), x: Math.round(run.sumX / run.pixels) })
    }
    // Merge bands a few pixels apart. A word whose middle scan line happens to hold four coloured
    // pixels rather than five splits into two — "Overview" and "Trip" both do, at this size — and
    // a raw band count then reports sixteen links on a screen with fourteen.
    const merged = []
    for (const band of bands) {
      const last = merged[merged.length - 1]
      if (last && band.y - last.y < 8) {
        last.y = Math.round((last.y + band.y) / 2)
      } else {
        merged.push({ ...band })
      }
    }
    return merged
  }, [b64])
}

// The contents list. The guide builds its own in JavaScript, off the h2s; there is no JavaScript
// here, so the app rebuilds it — and a contents list that does not take you anywhere is decoration.
await page.mouse.wheel(0, -4000)
await page.waitForTimeout(900)
const atTop = await inkProfile()
const links = await manualLinkRows()
// Close, then the thirteen sections. Asserting the count rather than trusting it: a contents list
// that lost a section would otherwise pass every check below by jumping somewhere else.
if (links.length !== 14) {
  fail(`the manual has ${links.length} links where it should have Close and thirteen sections`)
} else {
  pass('the manual lists all thirteen of the guide\'s sections')
}
// The last one, Troubleshooting, which is the far end of the guide: landing there cannot be
// mistaken for having stayed where you were.
const last = links[links.length - 1]
await at(last.x, last.y)
await page.waitForTimeout(1000)
const afterContentsTap = await inkProfile()
if (profileChange(atTop, afterContentsTap) < 0.3) {
  fail('tapping the contents list did not move the manual, so it is a list of words')
} else {
  pass('the contents list takes you to the section you tap')
}

// And out again. Close is the first link on the screen, in the header above everything else.
const close = (await manualLinkRows())[0]
await at(close.x, close.y)
await page.waitForTimeout(1200)
const backToTheCave = await panelGreen()
if (backToTheCave < 1000) {
  fail('closing the manual left it on the screen, so there is no way back to the cave')
} else {
  pass(`closing the manual gives the survey back (${backToTheCave} pixels of app bar and toolbar)`)
}

await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('demo'))); await page.waitForTimeout(900)
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('3d'))); await page.waitForTimeout(1400)
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

await at(...THREE_D_CLOSE()); await page.waitForTimeout(900)
// Not a timing problem after all: the DEBUG screenshot this captured on failure was byte-identical
// whether taken after 8 seconds of waiting or 15 - the plan view was already fully rendered,
// toolbar, palette, cross-section boxes and all, every single time. The canvas was never gone; the
// check was blind to it. `document.querySelectorAll` does not pierce shadow DOM, and evidently
// Compose Multiplatform 1.12.0's wasm target now mounts its canvas inside a shadow root - which is
// also exactly why the rest of this file gets "the" canvas once through Playwright's own
// shadow-piercing page.$('canvas') up top, rather than a fresh native query. Do the same here.
const backToTheSketch = (await page.$$('canvas')).length
if (backToTheSketch === 0) {
  fail('closing the 3D view left no canvas at all')
} else {
  pass('the 3D view closes back to the survey')
}

// ---- and a leg can be written down outright, from the Tools menu ----------------------------
// `action_add_leg`, which goes through `LegDialogs.addStation` and is a different thing from *Add
// reading* on the field bar. That button stands in for the instrument, so a typed reading is held
// to the instrument's rules and one of them is kept as a splay — three agreeing ones make a
// station, which is what the three readings above just did. This writes down what a surveyor
// already knows: a leg out of a paper book, or a join onto a station somebody else surveyed, is
// not three repeats of anything and its far end usually has a name already.
//
// Last of the full-size checks, deliberately. It adds a station, and every check above reads a
// survey it expects to be a particular shape — the first attempt at putting this beside the
// other manual-entry checks broke four of them, none of which mentioned a station count.
const stationsBeforeOutright = await savedStationNames()
await at(...(await overflowButton())); await page.waitForTimeout(500)
await at(...(await menuRow('add-leg'))); await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-add-leg.png') })

const addLegRows = await dialogTextRows()
if (addLegRows.length === 0) {
  fail('Add a leg opened nothing')
} else {
  await retype(await addLegRow('distance'), '6.5')
  await retype(await addLegRow('azimuth'), '210')
  await retype(await addLegRow('inclination'), '0')
  await retype(await addLegRow('to'), 'AV12')
  await retype(await addLegRow('note'), 'joins the old survey')
  await at(...(await addLegRow('add'))); await page.waitForTimeout(900)

  const after = await savedStationNames()
  const made = after.filter((name) => !stationsBeforeOutright.includes(name))
  if (!after.some((name) => name.endsWith('/AV12'))) {
    fail(
      `the leg did not make AV12 — the surveys hold ${after.join(', ') || 'nothing'}`)
  } else if (made.length !== 1) {
    fail(`one reading made ${made.length} stations: ${made.join(', ')}`)
  } else {
    pass(`a leg can be written down outright, with its station named (${made[0]})`)
  }

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
let small = box
const tapSmall = (x, y) => page.mouse.click(small.x + x, small.y + y)
const smallToolRow = small.height - 20
const smallColumn = small.width / 9

await page.screenshot({ path: join(shotDir, 'field-small-screen.png') })

// ---- the table's last column is reachable on the smaller phone -------------------------------
// Five fixed-width columns come to 396 pixels and the padding to 24 more: exactly the 420-pixel
// window everything above ran in, and forty-five too many for an iPhone SE. The header alone was
// scrollable and the rows were not, so the inclination — the reading that says whether a passage
// goes up or down — ran off the right of every row with no way to reach it, and had the header
// ever been dragged its labels would have come away from the numbers under them.
//
// A touch drag, because that is what a phone does and what Compose treats as a scroll: a mouse
// drag on a scrollable is not one, which is why every other drag in this file marks the paper.
// Touch emulation is turned on for this check alone rather than for the whole run, so the two
// hundred checks above go on being what they were.
// The tabs are right-aligned in the app bar, so on a narrower phone they move left by exactly the
// difference in width — not proportionally. Scaling by 375/420 lands between two icons and, on the
// way back, selected the extended elevation instead of the plan; every check after this one then
// ran against the wrong drawing, and the one that noticed was three screens later.
const smallTable = [small.width - (420 - TABLE_TAB[0]), TABLE_TAB[1]]
const smallPlan = [small.width - (420 - PLAN_TAB[0]), PLAN_TAB[1]]
await tapSmall(...smallTable); await page.waitForTimeout(900)

/** The x of the rightmost ink in a band of rows, or null if the band is blank. */
const rightmostInk = async (fromY, toY) => {
  const b64 = (await page.screenshot({ clip: small })).toString('base64')
  return page.evaluate(async ([data, top, bottom]) => {
    const img = new Image()
    await new Promise((r) => { img.onload = r; img.src = 'data:image/png;base64,' + data })
    const c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    const ctx = c.getContext('2d')
    ctx.drawImage(img, 0, 0)
    const px = ctx.getImageData(0, 0, c.width, c.height).data
    for (let x = c.width - 1; x >= 0; x--) {
      for (let y = top; y <= Math.min(bottom, c.height - 1); y++) {
        const i = (y * c.width + x) * 4
        if (Math.max(px[i], px[i + 1], px[i + 2]) < 150) return x
      }
    }
    return null
  }, [b64, fromY, toY])
}

// The header band and a band of data rows, measured from the rendered page.
const HEADER_BAND = [58, 76]
const ROWS_BAND = [140, 260]
const headerBefore = await rightmostInk(...HEADER_BAND)
const rowsBefore = await rightmostInk(...ROWS_BAND)

const cdp = await ctx.newCDPSession(page)
await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 })
const touchAt = (type, x, y) =>
  cdp.send('Input.dispatchTouchEvent', {
    type,
    touchPoints: type === 'touchEnd' ? [] : [{ x: small.x + x, y: small.y + y }],
  })
await touchAt('touchStart', 320, 200)
for (let x = 320; x >= 180; x -= 10) { await touchAt('touchMove', x, 200); await page.waitForTimeout(16) }
await touchAt('touchEnd', 180, 200)
await page.waitForTimeout(800)
await page.screenshot({ path: join(shotDir, 'field-small-table-scrolled.png') })

const headerAfter = await rightmostInk(...HEADER_BAND)
const rowsAfter = await rightmostInk(...ROWS_BAND)
await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: false })
await cdp.detach()

// And then a pixel out and back, which is not superstition.
//
// Compose lays out differently for a coarse pointer than for a fine one — the app bar is 52 pixels
// of green with a mouse and 36 with a finger — and turning the emulation off does not re-run the
// layout on its own. Without the nudge the app stays in its touch layout for the rest of the file,
// and the first check to notice is the full-screen one three screens later, complaining that the
// app bar it is about to hide is already short. Which is exactly the sort of failure that gets
// blamed on the check that reports it.
await page.setViewportSize({ width: 374, height: 667 }); await page.waitForTimeout(300)
await page.setViewportSize({ width: 375, height: 667 }); await page.waitForTimeout(600)
small = await (await page.$('canvas')).boundingBox()

if (headerBefore === null || rowsBefore === null) {
  fail('the table showed nothing on the small phone, so this check cannot fail')
} else if (headerBefore < small.width - 4) {
  fail(
    `the table's last column was already clear of the edge (${headerBefore} of ${small.width}),` +
      ' so there is nothing here to scroll to')
} else if (headerAfter === null || headerAfter >= small.width - 4) {
  fail('dragging the table sideways did not bring the last column into view')
} else if (Math.abs((headerBefore - headerAfter) - (rowsBefore - rowsAfter)) > 6) {
  fail(
    'the header and the rows moved by different amounts, so the labels no longer sit over the' +
      ` numbers (header ${headerBefore}->${headerAfter}, rows ${rowsBefore}->${rowsAfter})`)
} else {
  pass(
    'the table scrolls sideways as one on a small phone, so the last column can be read' +
      ` (${headerBefore} to ${headerAfter} of ${small.width})`)
}

await tapSmall(...smallPlan); await page.waitForTimeout(700)

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
await at(...(await menuRow('fullscreen'))); await page.waitForTimeout(900)
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
await at(...(await menuRow('about'))); await page.waitForTimeout(900)
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

// ---- and sideways, which is the keyboard case in disguise -----------------------------------
// An iPhone SE turned over is 667x375, and 375 is about what a portrait phone has left when a
// keyboard takes a third of it. This file has never had a keyboard — a headless browser has not
// got one — so the vertical squeeze has gone untested in the case it was written for.
//
// It cannot be simulated, but it can be *reproduced*: a dialog with fields in a 375-pixel-tall
// window is the same layout problem whichever way the phone is held. What this does not test is
// the other half — whether iOS reports the keyboard's height as a window inset at all, which only
// a device can answer. Said plainly rather than implied, because a check that half-covers a case
// is worse than no check if it is read as covering it.
//
// Landscape is also how a wide passage actually gets drawn, so this is worth having on its own.
await page.setViewportSize({ width: 667, height: 375 })
await page.waitForTimeout(1200)
box = await (await page.$('canvas')).boundingBox()
const wide = box
await page.screenshot({ path: join(shotDir, 'field-landscape.png') })

// The sketch still works sideways: same toolbar arithmetic, same stroke.
const wideColumn = wide.width / 9
const wideToolRow = wide.height - 20
const wideInk = async () => {
  const b64 = (await page.screenshot({ clip: wide })).toString('base64')
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
    // The middle of what is left of the canvas once the chrome has taken its share.
    for (let y = 90; y < 170; y++) {
      for (let x = 120; x < 520; x++) {
        const i = (y * c.width + x) * 4
        const lightest = Math.max(px[i], px[i + 1], px[i + 2])
        if (lightest < 200) ink += 200 - lightest
      }
    }
    return ink
  }, [b64])
}
await page.mouse.click(wide.x + wideColumn * 1.5, wide.y + wideToolRow)
await page.waitForTimeout(500)
const wideInkBefore = await wideInk()
await page.mouse.move(wide.x + 140, wide.y + 110)
await page.mouse.down()
await page.mouse.move(wide.x + 500, wide.y + 150, { steps: 14 })
await page.mouse.up()
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'field-landscape-drawn.png') })
if (!((await wideInk()) > wideInkBefore)) {
  fail('turned sideways the toolbar or the canvas was not where it should be — no stroke')
} else {
  pass('turned sideways the toolbar still works and the sketch still takes a stroke')
}

// A dialog with a text field in it, in 375 pixels of height. New survey is the one always
// reachable whatever the run has left on screen, and it is a field and two buttons — the shape
// every dialog that needs a keyboard has.
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('new'))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-landscape-dialog.png') })

const wideTop = await dialogTop()
const wideHeight = await dialogHeight()
if (wideTop === null || wideHeight === null) {
  fail('the new-survey dialog did not open sideways')
} else if (wideTop + wideHeight > wide.height) {
  fail(
    `sideways the dialog runs from ${wideTop} to ${wideTop + wideHeight} in ${wide.height} ` +
      'pixels of height, so its buttons are off the bottom — which is what a keyboard would do',
  )
} else {
  pass(
    `a dialog with a field in it fits 375 pixels of height (${wideHeight} tall at y=${wideTop})`,
  )
  // And it is usable, not merely present: the field takes a name and the button takes it.
  const rows = await dialogTextRows()
  const confirm = await dialogConfirm()
  if (confirm === null || rows.length === 0) {
    fail('sideways the new-survey dialog has no field or no button on screen')
  } else {
    await page.mouse.click(wide.x + wide.width / 2, wide.y + wideTop + wideHeight / 2)
    await page.waitForTimeout(250)
    await page.keyboard.type('Sideways', { delay: 25 })
    await page.waitForTimeout(250)
    await at(...confirm); await page.waitForTimeout(1200)
    if ((await dialogTop()) !== null) {
      fail('sideways the new-survey dialog would not close from its own button')
    } else {
      pass('and it can be typed into and confirmed, which is the whole point of the keyboard case')
    }
  }
}

// ---- and the same phone the right way up, with the keyboard up ------------------------------
// The check above squeezes the height and says honestly that it is the keyboard case in
// disguise. It is - but only half of it. A phone turned sideways is 667 pixels *wide*, and a
// dialog with 667 pixels to lay out in has room the same dialog does not have in portrait. What a
// keyboard actually leaves an iPhone SE is 375 by 375: narrow and short at once, and neither of
// the two viewports this file has run so far is that.
//
// So this is the shape itself rather than a stand-in for it. What it still cannot prove is the
// other half the section above names: whether iOS reports the keyboard's height as a window inset
// at all. That needs a phone. What it does prove is that if iOS does, the dialog underneath is a
// dialog you can still finish.
await page.setViewportSize({ width: 375, height: 375 })
await page.waitForTimeout(1200)
box = await (await page.$('canvas')).boundingBox()
const squeezed = box
await page.screenshot({ path: join(shotDir, 'field-portrait-squeezed.png') })

// Recounted rather than reused: the landscape check above created a survey, so the saved list is
// one longer than it was when `smallSaved` was taken and every menu row below it has moved.
const squeezedSaved = await page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})

await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('new'))); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'field-portrait-squeezed-dialog.png') })

const squeezedTop = await dialogTop()
const squeezedHeight = await dialogHeight()
if (squeezedTop === null || squeezedHeight === null) {
  fail('the new-survey dialog did not open on a 375x375 screen')
} else if (squeezedTop + squeezedHeight > squeezed.height) {
  fail(
    `with a keyboard up the dialog runs from ${squeezedTop} to ` +
      `${squeezedTop + squeezedHeight} in ${squeezed.height} pixels of height, so its buttons ` +
      'are off the bottom',
  )
} else {
  const squeezedRows = await dialogTextRows()
  const squeezedConfirm = await dialogConfirm()
  if (squeezedConfirm === null || squeezedRows.length === 0) {
    fail('on a 375x375 screen the new-survey dialog has no field or no button on screen')
  } else {
    await page.mouse.click(
      squeezed.x + squeezed.width / 2,
      squeezed.y + squeezedTop + squeezedHeight / 2,
    )
    await page.waitForTimeout(250)
    await page.keyboard.type('Squeezed', { delay: 25 })
    await page.waitForTimeout(250)
    await at(...squeezedConfirm); await page.waitForTimeout(1200)
    if ((await dialogTop()) !== null) {
      fail('on a 375x375 screen the new-survey dialog would not close from its own button')
    } else {
      pass(
        'a dialog can be opened, typed into and confirmed in 375 by 375, which is what a ' +
          `keyboard leaves a phone (${squeezedHeight} tall at y=${squeezedTop})`,
      )
    }
  }
}

// Back to landscape, because everything below was written against that window.
await page.setViewportSize({ width: 667, height: 375 })
await page.waitForTimeout(1200)
box = await (await page.$('canvas')).boundingBox()

// ---- the theme, which was forgotten every time the app closed -------------------------------
// `pref_theme` is a three-value list in the Android app — auto, light, dark — applied through
// `AppCompatDelegate.setDefaultNightMode`. This port had a two-state toggle on the menu that was a
// plain `var`, so it started light on every run.
//
// That is not cosmetic underground. The phone is the brightest object in a cave, the OS kills a
// backgrounded app while it is in a pocket between stations, and the surveyor gets a
// full-brightness white page in the face at the next one — after which their night vision is gone
// for a quarter of an hour.
//
// Measured as the mean of each pixel's brightest channel over the whole canvas: the light ground
// is #F5F5F5 and the dark one #121212, so the two are nowhere near each other and no threshold
// needs to be fine.
const meanBrightness = async () => {
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
    let total = 0
    for (let i = 0; i < px.length; i += 4) {
      total += Math.max(px[i], px[i + 1], px[i + 2])
    }
    return Math.round(total / (px.length / 4))
  }, [b64])
}

const themeLight = await meanBrightness()

// Automatic means the phone's own setting, which is what `prefers-color-scheme` is in a browser
// and the trait collection on iOS. Nothing else here has ever asked the platform anything, so this
// is the check that the wiring exists rather than that a constant is false.
await page.emulateMedia({ colorScheme: 'dark' })
await page.waitForTimeout(1200)
const themeFollowed = await meanBrightness()
await page.screenshot({ path: join(shotDir, 'field-theme-automatic-dark.png') })
if (!(themeLight > 150 && themeFollowed < 110)) {
  fail(
    `Automatic did not follow the phone into dark (${themeLight} then ${themeFollowed})`,
  )
} else {
  pass('on Automatic the app follows the phone into dark, and back')
}
await page.emulateMedia({ colorScheme: 'light' })
await page.waitForTimeout(1200)

// And the surveyor can overrule it, which is the point of the other two values: a cave is dark at
// noon, and Automatic on a phone only ever answers "is it evening".
// Counted again rather than reusing the count from the small-screen block: a survey has been
// created since. Neither Settings nor the theme list grows with the library, so it happens not to
// matter here — but a number that is wrong and harmless is the one that bites the next edit.
const themeSaved0 = await page.evaluate(() => {
  const prefix = 'sexytopo:f:surveys/'
  const names = Object.keys(localStorage)
    .filter((k) => k.startsWith(prefix))
    .map((k) => k.slice(prefix.length).split('/')[0])
  return new Set(names).size
})
// `pref_theme` is a three-value list preference on `preferences_general.xml`, not a menu page:
// Settings → System → General, and three chips on the dialog that opens.
await at(...(await overflowButton())); await page.waitForTimeout(600)
await at(...(await menuRow('general'))); await page.waitForTimeout(900)
await at(...(await themeChip('dark'))); await page.waitForTimeout(400)
await at(...(await settingsSave())); await waitForDialogToClose()
await page.waitForTimeout(500)
await page.screenshot({ path: join(shotDir, 'field-theme-dark.png') })

const themeChosen = await meanBrightness()
const themeSaved = await page.evaluate(() =>
  localStorage.getItem('sexytopo:f:preferences.txt'),
)
if (!(themeChosen < 110)) {
  fail(`choosing Dark left the screen light (${themeChosen})`)
} else if (!themeSaved || !themeSaved.includes('theme=dark')) {
  fail(`the theme was not written to storage (${JSON.stringify(themeSaved)})`)
} else {
  pass('the theme can be set against the phone, and is written down')
}

// The half that was missing, and the half no amount of reading the code proves: reload the page
// with the browser still saying light, and the app has to come back dark.
await page.reload({ waitUntil: 'load' })
await page.waitForSelector('canvas', { timeout: 60000 })
await page.waitForTimeout(4000)
box = await (await page.$('canvas')).boundingBox()
await page.screenshot({ path: join(shotDir, 'field-theme-reopened.png') })
const themeReopened = await meanBrightness()
if (!(themeReopened < 110)) {
  fail(
    `the app came back light after being closed (${themeReopened}); dark mode is session-only`,
  )
} else {
  pass('and it is still dark the next time the app opens, which is the whole point of it')
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
