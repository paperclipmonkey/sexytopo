// The Web Bluetooth transport, driven by a fake instrument.
//
// This is the only way anything about a radio gets verified without one. `CoreBluetoothTransport`
// cannot be exercised at all — the iOS simulator has no Bluetooth stack — but Web Bluetooth is
// JavaScript, so a stub standing where `navigator.bluetooth` would be can be made to behave
// exactly like a DistoX-BLE: advertise, connect, expose the Nordic UART service, and push
// measurement notifications.
//
// What that checks is the whole chain the port has never been able to check: the profile's name
// prefix and UUIDs reaching the browser API, the notification arriving as a frame, the decoder
// reading it, the acknowledgement being written back, and the triple-shot rule turning three of
// them into a station. Everything except the radio itself.
//
//   node instrument.mjs <url> [screenshotDir]
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:8080/index.html'
const shotDir = process.argv[3] ?? 'instrument-screenshots'
mkdirSync(shotDir, { recursive: true })

const failures = []
const fail = (m) => { failures.push(m); console.error(`FAIL  ${m}`) }
const pass = (m) => console.log(`ok    ${m}`)

const launch = {}
if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH
if (process.env.SMOKE_PROXY) launch.proxy = { server: process.env.SMOKE_PROXY }
const browser = await chromium.launch(launch)
const ctx = await browser.newContext({ viewport: { width: 420, height: 900 } })

// A DistoX-BLE that is not there. Installed before the page runs, so the app finds it exactly
// where it would find the real API.
await ctx.addInitScript(() => {
  const NUS = '6e400001-b5a3-f393-e0a9-e50e24dcca9e'
  const NOTIFY = '6e400003-b5a3-f393-e0a9-e50e24dcca9e'
  const WRITE = '6e400002-b5a3-f393-e0a9-e50e24dcca9e'

  const record = {
    requested: null,
    connected: false,
    notifying: [],
    written: [],
    listeners: {},
  }
  window.__fakeInstrument = record

  const characteristic = (uuid) => ({
    uuid,
    addEventListener(type, handler) {
      record.listeners[uuid] = handler
    },
    async startNotifications() {
      record.notifying.push(uuid)
      return this
    },
    async writeValueWithResponse(bytes) {
      record.written.push(Array.from(bytes))
    },
    async writeValueWithoutResponse(bytes) {
      record.written.push(Array.from(bytes))
    },
    async writeValue(bytes) {
      record.written.push(Array.from(bytes))
    },
  })

  const characteristics = { [NOTIFY]: characteristic(NOTIFY), [WRITE]: characteristic(WRITE) }
  const service = {
    async getCharacteristic(uuid) {
      const found = characteristics[uuid]
      if (!found) throw new Error(`no characteristic ${uuid}`)
      return found
    },
  }

  navigator.bluetooth = {
    async requestDevice(options) {
      record.requested = JSON.parse(JSON.stringify(options))
      return {
        name: 'DistoXBLE-1234',
        addEventListener() {},
        gatt: {
          connected: false,
          async connect() {
            record.connected = true
            this.connected = true
            return {
              async getPrimaryService(uuid) {
                if (uuid !== NUS) throw new Error(`no service ${uuid}`)
                return service
              },
            }
          },
          disconnect() {
            this.connected = false
          },
        },
      }
    },
  }

  // Pushes one calibration reading, the way a DistoX-BLE does: identifier 0x02, then the
  // acceleration packet at offset 1 and the magnetic packet at offset 9. Both are ordinary
  // DistoX calibration packets — admin byte, then three little-endian signed 16-bit counts.
  window.__sendCalibration = (row) => {
    const frame = new Uint8Array(17)
    frame[0] = 0x02 // DistoX-BLE calibration identifier
    const put = (at, type, x, y, z) => {
      frame[at] = type
      const v = new DataView(frame.buffer)
      v.setInt16(at + 1, x, true)
      v.setInt16(at + 3, y, true)
      v.setInt16(at + 5, z, true)
    }
    put(1, 0x02, row[0], row[1], row[2]) // CALIBRATION_ACCELERATION
    put(9, 0x03, row[3], row[4], row[5]) // CALIBRATION_MAGNETIC
    const handler = record.listeners[NOTIFY]
    if (!handler) return false
    handler({ target: { value: new DataView(frame.buffer) } })
    return true
  }

  // Pushes one measurement notification, the way the instrument would.
  window.__sendMeasurement = (distanceMillimetres) => {
    const frame = new Uint8Array(17)
    frame[0] = 0x01 // DistoX-BLE measurement identifier
    frame[1] = 0x01 // the embedded packet's admin byte: a data packet
    frame[2] = distanceMillimetres & 0xff
    frame[3] = (distanceMillimetres >> 8) & 0xff
    const handler = record.listeners[NOTIFY]
    if (!handler) return false
    handler({ target: { value: new DataView(frame.buffer) } })
    return true
  }
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
const OVERFLOW = [box.width - 16, 26]
// The overflow menu, by name — the saved surveys sit in the middle, so every row below them moves
// as soon as this test has recorded one, which is exactly what happens between its two halves.
const MENU_BEFORE_SURVEYS = ['new', 'rename', 'trip']
const MENU_AFTER_SURVEYS =
  ['demo', 'export', 'instrument', 'stats', 'calibrate', 'import', 'surveying', 'dark']
function menuRow(name, savedSurveys) {
  const before = MENU_BEFORE_SURVEYS.indexOf(name)
  const after = MENU_AFTER_SURVEYS.indexOf(name)
  if (before < 0 && after < 0) throw new Error(`no menu item called ${name}`)
  const index = before >= 0 ? before : MENU_BEFORE_SURVEYS.length + savedSurveys + after
  return [312, 80 + 48 * index]
}
// The calibration dialog. Its layout is fixed once the first reading has arrived and added the
// "Last:" line; before that the buttons sit one line higher.
const INSTRUMENT_CLOSE = [212, 759]
const CALIBRATION_START = [103, 415]
const CALIBRATION_SOLVE = [103, 611]
const CALIBRATION_WRITE = [150, 700]
const FIRST_INSTRUMENT = [210, 306]

await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...menuRow('instrument', 0)); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'instrument-list.png') })

// ---- the profile reaches the browser API ---------------------------------------------------
await at(...FIRST_INSTRUMENT); await page.waitForTimeout(1200)
await page.screenshot({ path: join(shotDir, 'instrument-connected.png') })

const requested = await page.evaluate(() => window.__fakeInstrument.requested)
if (!requested) {
  fail('choosing an instrument never asked the browser for a device')
} else if (requested.filters?.[0]?.namePrefix !== 'DistoXBLE-') {
  fail(`the wrong name prefix reached the browser: ${JSON.stringify(requested.filters)}`)
} else if (!requested.optionalServices?.includes('6e400001-b5a3-f393-e0a9-e50e24dcca9e')) {
  fail(`the Nordic UART service was not requested: ${JSON.stringify(requested.optionalServices)}`)
} else {
  pass('the device profile reaches the browser with the right prefix and service')
}

const notifying = await page.evaluate(() => window.__fakeInstrument.notifying)
if (!notifying.includes('6e400003-b5a3-f393-e0a9-e50e24dcca9e')) {
  fail(`the app never subscribed to the measurement characteristic (${JSON.stringify(notifying)})`)
} else {
  pass('the app connects and subscribes to the notify characteristic')
}

// ---- a reading arrives, is acknowledged, and builds the survey -------------------------------
// Three identical shots, which is what a surveyor does and what the triple-shot rule needs.
for (let i = 0; i < 3; i++) {
  const sent = await page.evaluate(() => window.__sendMeasurement(10000))
  if (!sent) fail('the notification handler was never registered')
  await page.waitForTimeout(700)
}
await page.screenshot({ path: join(shotDir, 'instrument-readings.png') })

const written = await page.evaluate(() => window.__fakeInstrument.written)
if (written.length < 3) {
  fail(`only ${written.length} of 3 readings were acknowledged — the instrument would stop sending`)
} else {
  pass('every reading is acknowledged, so the instrument keeps sending')
}

const survey = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('.data.json'))
  if (!key) return null
  const json = JSON.parse(localStorage.getItem(key))
  return (json.stations ?? []).flatMap((s) => s.legs ?? [])
})
if (survey === null) {
  fail('nothing was saved, so no reading reached the survey')
} else if (!survey.some((leg) => leg.destination && leg.destination !== '-')) {
  fail(`three readings from the instrument did not make a station (${JSON.stringify(survey)})`)
} else {
  pass('three readings over Bluetooth promote to a station and are saved')
}

// ---- and the instrument can be calibrated ----------------------------------------------------
// The last part of the app that could not be tried at all without hardware. Beat Heeb's solver was
// ported and tested against the Android app's own datasets early on, and the packet decoders and
// the memory writes with it — but nothing could ask an instrument to start, so none of it had ever
// been driven end to end. Here the fake DistoX-BLE is put into calibration mode, fed sixteen real
// readings, and the coefficients are written back to it.
//
// The readings are the real 56-shot dataset the shared tests fit — one of the two from the Android
// app's own test suite — so this ends in the answer that data is known to produce (0.60 in 43
// iterations) rather than in whatever noise gives. Those numbers are pinned on three targets by the
// shared tests; what is checked here is the chain either side of them.
const CALIBRATION_ROWS = [
  [12545, 155, 1529, 17916, 5305, 5435],
  [12563, -490, 660, 18069, -5257, 5596],
  [12529, 90, -95, 17831, -6762, -4037],
  [12558, 846, 475, 17559, 4644, -5383],
  [-15265, -256, 1275, -15908, -7485, 3364],
  [-15258, 1029, 1000, -15910, 3346, 7294],
  [-15250, 674, -217, -16244, 6953, -2846],
  [-15293, -394, 8, -16231, -3702, -7191],
  [-2256, 14202, 633, 6650, 17342, 419],
  [-2191, 2272, 14380, 7225, 2625, 17556],
  [-2288, -13659, 2137, 6899, -17969, 1800],
  [-2473, -1891, -13041, 6168, -3497, -17212],
  [-185, 1018, 14485, -4364, -295, 17751],
  [-320, 14126, -598, -5040, 17503, 331],
  [-366, 146, -13215, -5376, 677, -17361],
  [-443, -13747, 261, -5005, -18035, -2011],
  [-501, 14193, 556, 2643, 16880, 7923],
  [-350, 838, 14540, 3171, -6868, 17092],
  [-516, -13762, 681, 2425, -17529, -7635],
  [-633, 131, -13217, 1960, 6851, -16472],
  [-2126, 14229, 644, -1194, 17018, -5863],
  [-2023, 427, 14551, -408, 6673, 17513],
  [-2090, -13727, 1481, -531, -17288, 7172],
  [-2189, -94, -13173, -1229, -7523, -17129],
  [-12118, 836, 9421, -15525, 5225, 7209],
  [-12240, -8542, 916, -15400, -7474, 5173],
  [-12330, 1066, -7979, -15801, -4817, -7616],
  [-12401, 8971, 924, -15940, 6965, -4371],
  [9382, -81, 9566, 17469, -5886, 6897],
  [9434, 9073, 1468, 17352, 6354, 6137],
  [9322, 749, -8137, 16983, 5346, -6285],
  [9509, -8554, 133, 17201, -7039, -5651],
  [-8218, -1311, 12591, -11536, -7259, 11530],
  [-8315, -11840, -715, -12035, -12247, -6960],
  [-8452, 2007, -11186, -12306, 6859, -11071],
  [-8352, 12387, 2087, -11803, 11643, 7393],
  [5750, 112, 12714, 13993, 4513, 12914],
  [5527, -11988, 329, 13716, -13337, 4205],
  [5496, 1032, -11263, 13137, -4538, -12932],
  [5583, 12349, 1139, 13272, 12558, -3814],
  [-9520, -1257, 11869, -4428, -7482, 15834],
  [-9544, -11143, 376, -4929, -17271, -5698],
  [-9617, 1520, -10450, -5349, 6365, -15753],
  [-9672, 11460, 2362, -4926, 15805, 8037],
  [6595, -878, 12138, 6748, 2411, 17732],
  [6529, 11647, -813, 5896, 16263, -5711],
  [6491, -2406, -10443, 5805, -8548, -15896],
  [6631, -10996, 3212, 6761, -16322, 7469],
  [-10512, -165, 11212, -6355, 3673, 16712],
  [-10644, -10193, -353, -6572, -17075, 2599],
  [-10686, 797, -9668, -7297, -4341, -16321],
  [-10709, 10726, 1640, -7118, 16365, -2443],
  [7782, -10321, -376, 8261, -16015, -7317],
  [7631, -555, -9738, 7758, 3780, -16056],
  [7806, 10780, 805, 8383, 15902, 6079],
  [7683, -270, -9688, 7841, 4231, -15895],
]

// The instrument dialog is still up from the connection checks, and its scrim would swallow the
// first click at the overflow menu.
await at(...INSTRUMENT_CLOSE); await page.waitForTimeout(700)
await at(...OVERFLOW); await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'calibration-menu.png') })
await at(...menuRow('calibrate', 1)); await page.waitForTimeout(900)
await page.screenshot({ path: join(shotDir, 'calibration-open.png') })

const writesBefore = (await page.evaluate(() => window.__fakeInstrument.written)).length
await at(...CALIBRATION_START); await page.waitForTimeout(700)

const startCommand = await page.evaluate(() => window.__fakeInstrument.written.slice(-1)[0])
// A `data:`-framed 0x31, which is START_CALIBRATION.
if (!startCommand || !startCommand.includes(0x31)) {
  fail(`starting calibration did not send the command (${JSON.stringify(startCommand)})`)
} else {
  pass('the instrument is told to enter calibration mode')
}

for (const row of CALIBRATION_ROWS) {
  const sent = await page.evaluate((r) => window.__sendCalibration(r), row)
  if (!sent) fail('the calibration notification handler was never registered')
  await page.waitForTimeout(60)
}
await page.screenshot({ path: join(shotDir, 'calibration-readings.png') })

await at(...CALIBRATION_SOLVE); await page.waitForTimeout(3000)
await page.screenshot({ path: join(shotDir, 'calibration-solved.png') })
await at(...CALIBRATION_WRITE); await page.waitForTimeout(1500)
await page.screenshot({ path: join(shotDir, 'calibration-written.png') })

const allWrites = await page.evaluate(() => window.__fakeInstrument.written)
// Twelve four-byte memory writes from 0x8010, each `[0x39, addrLow, addrHigh, b0..b3]`.
const coefficientWrites = allWrites.filter((w) => w.includes(0x39) && w.length >= 7)
if (coefficientWrites.length < 12) {
  fail(
    `only ${coefficientWrites.length} of 12 coefficient blocks reached the instrument ` +
      `(${allWrites.length - writesBefore} writes in total)`,
  )
} else {
  pass('a calibration is solved and its coefficients written back to the instrument')
}

// ---- and an interrupted calibration comes back ------------------------------------------------
// Fifty-six shots is twenty minutes, and twenty minutes underground is long enough for a phone to
// be dropped or a battery to go flat. The run is written on every reading, in the Android app's own
// JSON format, so the two can read each other's calibrations.
const storedCalibration = await page.evaluate(() => {
  const key = Object.keys(localStorage).find((k) => k.endsWith('calibration.json'))
  return key ? JSON.parse(localStorage.getItem(key)).length : null
})
if (storedCalibration === null) {
  fail('the calibration was never written to storage, so a flat battery would lose it')
} else if (storedCalibration !== CALIBRATION_ROWS.length) {
  fail(`${storedCalibration} of ${CALIBRATION_ROWS.length} readings were stored`)
} else {
  pass('the calibration is saved as it is taken, so an interrupted run is not lost')
}

if (pageErrors.length > 0) {
  fail(`the page threw while connected:\n      ${pageErrors.slice(0, 3).join('\n      ')}`)
}

await browser.close()
if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`)
  process.exit(1)
}
console.log('\nInstrument test passed.')
