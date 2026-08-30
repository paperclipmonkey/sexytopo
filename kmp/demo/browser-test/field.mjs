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
const STATION_NAME = [210, 352]
const STATION_COMMENT = [210, 428]
const STATION_EE_LEFT = [102, 536]
const STATION_SAVE = [317, 608]
// The overflow menu lists the saved surveys between "Rename" and "Demo cave", so Export sits one
// row lower here than it does with an empty library. One saved survey by this point: Swildons.
const MENU_EXPORT = [312, 272]
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

// ---- and the survey can leave the phone as a file ------------------------------------------
// The clipboard reaches an email. Only a file reaches Therion, and a survey that cannot get into
// Therion is a weekend of somebody's life spent producing something they then have to type up
// again from a photograph of a screen.
await at(...OVERFLOW); await page.waitForTimeout(500)
await page.screenshot({ path: join(shotDir, 'field-menu.png') })
await at(...MENU_EXPORT); await page.waitForTimeout(900)
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
  } else {
    pass('the exported file carries the survey, today\'s date and the named station')
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

if (pageErrors.length > 0) {
  fail(`the page threw while being used:\n      ${pageErrors.slice(0, 3).join('\n      ')}`)
}

await browser.close()
if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`)
  process.exit(1)
}
console.log('\nField workflow test passed.')
