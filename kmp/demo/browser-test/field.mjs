// The field workflow: can a caver actually record a survey on an iPhone with no signal?
//
// The smoke test asks whether the app draws. This asks whether it *works* — create a named survey,
// type readings the way a surveyor reads them off a DistoX display, have three agreeing ones
// promote to a station, correct one that was typed wrong, throw away one that was not worth
// keeping, keep the lot across a restart, and still open with the network cut. Each of those is a
// thing that would make the app useless underground if it broke, and none of them is visible in a
// screenshot.
//
//   node field.mjs <url> [screenshotDir]
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
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
const ctx = await browser.newContext({ viewport: { width: 420, height: 900 } })
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

// Positions are computed from the canvas box, so moving a control a few pixels does not break the
// test while moving it somewhere else rightly does.
const OVERFLOW = [box.width - 16, 26]
const MENU_NEW = [336, 80]
const NAME_FIELD = [210, 442]
const NAME_CONFIRM = [312, 518]
const ADD_READING = [74, 790]
const FIELD_DISTANCE = [140, 384]
const FIELD_AZIMUTH = [280, 384]
const FIELD_INCLINATION = [140, 458]
const SIGN_TOGGLE = [255, 454]
const ADD_SPLAY = [225, 576]
const ADD_LEG = [309, 576]
const TABLE_TAB = [281, 26]
const PLAN_TAB = [325, 26]
const TABLE_ROW = (n) => [210, 66 + 26 * n]
const ACT_EDIT = [276, 448]
const ACT_DELETE_SPLAY = [292, 544]
const EDIT_DISTANCE = [140, 384]
const EDIT_SAVE = [309, 552]
const CONFIRM_DELETE = [292, 496]

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
async function reading(d, a, i, splay = false) {
  await at(...ADD_READING); await page.waitForTimeout(700)
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
  if (!key) return null
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
const promoted = (await savedLegs())?.find(isConnecting)
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
await reading(1.5, 200, 0, true) // a splay, so the correction has something disposable to work on

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
  await at(...EDIT_DISTANCE); await page.waitForTimeout(250)
  await page.keyboard.press('Control+a')
  await page.keyboard.type('2.75', { delay: 25 })
  await page.waitForTimeout(250)
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
