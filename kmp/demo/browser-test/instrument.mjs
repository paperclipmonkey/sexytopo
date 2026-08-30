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
// With an empty library: New(80) Rename(128) Trip(176) Demo(224) Export(272) Instrument(320)
const MENU_INSTRUMENT = [312, 320]
const FIRST_INSTRUMENT = [210, 306]

await at(...OVERFLOW); await page.waitForTimeout(600)
await at(...MENU_INSTRUMENT); await page.waitForTimeout(900)
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

if (pageErrors.length > 0) {
  fail(`the page threw while connected:\n      ${pageErrors.slice(0, 3).join('\n      ')}`)
}

await browser.close()
if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`)
  process.exit(1)
}
console.log('\nInstrument test passed.')
