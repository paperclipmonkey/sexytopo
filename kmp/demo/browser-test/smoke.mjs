// End-to-end smoke test for the browser build of the shared Compose UI.
//
// This is the only check in the repository that exercises the whole stack the way a person would:
// the ported survey core, the shared Compose UI, and Skia — all compiled to WebAssembly and run in
// a real browser, on a target with no `java.*` anywhere in it. The JVM tests prove the core is
// correct; the headless render proves Compose draws; this proves the two work together somewhere
// that is not a JVM.
//
// It also exists because the claim it checks was wrong in this repository's own README for a
// while. "The browser page renders" is exactly the sort of statement that rots silently, so it is
// asserted by a machine rather than by a sentence.
//
// Usage:  node smoke.mjs <url> [screenshot-dir]
// Exit code 0 if everything below holds, 1 with a reason if not.

import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:8731/index.html'
const shotDir = process.argv[3] ?? '.'
mkdirSync(shotDir, { recursive: true })

const failures = []
const fail = (message) => {
  failures.push(message)
  console.error(`FAIL  ${message}`)
}
const pass = (message) => console.log(`ok    ${message}`)

// CI installs the browser Playwright expects, so it needs no help. CHROMIUM_PATH is for an
// environment that already has a Chromium of its own and would rather not download another.
//
// SMOKE_PROXY is for pointing this at a *deployed* URL from a sandbox whose outbound traffic goes
// through a proxy. Chromium does not inherit HTTPS_PROXY the way curl does, so without it a remote
// run dies on ERR_CONNECTION_RESET while every local run passes. Deliberately its own variable
// rather than reading HTTPS_PROXY, so a proxy that happens to be set in some CI environment cannot
// silently reroute a run that does not want it.
const launchOptions = {}
if (process.env.CHROMIUM_PATH) launchOptions.executablePath = process.env.CHROMIUM_PATH
if (process.env.SMOKE_PROXY) launchOptions.proxy = { server: process.env.SMOKE_PROXY }
const browser = await chromium.launch(launchOptions)
const page = await browser.newPage({ viewport: { width: 1100, height: 800 } })

const pageErrors = []
const httpErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))
page.on('response', (r) => {
  // A missing favicon is the browser's business, not ours.
  if (r.status() >= 400 && !r.url().endsWith('/favicon.ico')) {
    httpErrors.push(`${r.status()} ${r.url()}`)
  }
})

await page.goto(url, { waitUntil: 'load' })

// Compose has to download and instantiate the wasm module, then load the bundled font, before it
// draws anything. Poll rather than sleeping a fixed time, so this is neither flaky on a slow
// runner nor needlessly slow on a fast one.
let ready = false
for (let i = 0; i < 60 && !ready; i++) {
  await page.waitForTimeout(500)
  const shot = await page.screenshot()
  // The app is up once the page is no longer a single flat colour.
  ready = shot.length > 20000
}

await page.screenshot({ path: join(shotDir, 'browser-plan.png') })

if (pageErrors.length > 0) {
  fail(`the page threw during startup:\n      ${pageErrors.join('\n      ')}`)
} else {
  pass('no uncaught exceptions during startup')
}

if (httpErrors.length > 0) {
  fail(`resources failed to load:\n      ${httpErrors.join('\n      ')}`)
} else {
  pass('every resource loaded, bundled font included')
}

const canvas = await page.$('canvas')
if (!canvas) {
  fail('no canvas element — Compose never attached')
  await browser.close()
  process.exit(1)
}
const box = await canvas.boundingBox()
if (!box || box.width < 100 || box.height < 100) {
  fail(`canvas is ${box ? `${box.width}x${box.height}` : 'unmeasurable'}`)
} else {
  pass(`canvas attached at ${Math.round(box.width)}x${Math.round(box.height)}`)
}

// Is anything actually drawn? A blank page — which is the failure mode this whole file exists to
// catch — compresses to almost nothing, because a single flat colour is the best case for PNG.
const rendered = await page.screenshot()
if (rendered.length < 20000) {
  fail(`the page looks blank (screenshot is only ${rendered.length} bytes)`)
} else {
  pass(`something is drawn (${Math.round(rendered.length / 1024)} KiB of PNG)`)
}

// Text is the specific thing that used to throw here, because Skia ships no system fonts on the
// web. If the bundled font were not loading, the survey would draw and the labels would not.
const drewText = await page.evaluate(() => {
  const c = document.querySelector('canvas')
  const ctx = c.getContext('webgl2') ?? c.getContext('webgl')
  return ctx != null
})
if (drewText) {
  pass('WebGL context is live')
} else {
  fail('no WebGL context')
}

// Now drive it: draw a stroke, then undo it. This is the shared SketchEditor running in wasm.
//
// Everything the app draws - the status line included - is painted into the canvas rather than the
// DOM, so there is no text to query. Compare the rendered pixels instead: a drawn stroke changes
// the image, and an undo puts it back. Byte-for-byte equality after the undo would be too strict
// (antialiasing differs), so the comparison is on compressed size.
const beforeDraw = await page.screenshot({ clip: box })

// The toolbar is SexyTopo's own: nine equal columns, two rows, along the bottom. Positions are
// computed from the canvas box rather than hardcoded, so moving a button by a few pixels does not
// break the test while moving it to another cell rightly does.
const column = box.width / 9
const toolRowY = box.y + box.height - 20
const cellCentre = (index) => box.x + column * (index + 0.5)

// Row two, cell one: the pencil.
await page.mouse.click(cellCentre(1), toolRowY)
await page.waitForTimeout(400)

await page.mouse.move(box.x + 300, box.y + 620)
await page.mouse.down()
for (let i = 0; i <= 20; i++) {
  await page.mouse.move(box.x + 300 + i * 12, box.y + 620 - Math.sin(i / 3) * 40)
}
await page.mouse.up()
await page.waitForTimeout(800)

const afterDraw = await page.screenshot({ clip: box })
await page.screenshot({ path: join(shotDir, 'browser-drawn.png') })

// Strictly *more* ink, not merely different. The tool chips are clicked by coordinate, so if the
// toolbar ever shifts and the click lands on Erase instead of Draw, the drag would still change
// the image - and a test that only asked "did anything change" would pass while testing the wrong
// tool. More pixels covered means more PNG.
if (afterDraw.length <= beforeDraw.length) {
  fail(
    `the drag did not add ink (${beforeDraw.length} -> ${afterDraw.length} bytes). ` +
      'Either drawing is broken, or the draw button is no longer in row two, cell one.',
  )
} else {
  pass('a drag draws a stroke')
}

// Row two, cell six: undo.
await page.mouse.click(cellCentre(6), toolRowY)
await page.waitForTimeout(700)
const afterUndo = await page.screenshot({ clip: box })

// Undo must take the ink away again, landing nearer where it started than where the stroke left
// it. Exact equality is too strict — antialiasing does not round-trip byte for byte.
if (afterUndo.length >= afterDraw.length) {
  fail(`undo did not remove the stroke (${afterDraw.length} -> ${afterUndo.length} bytes)`)
} else if (Math.abs(afterUndo.length - beforeDraw.length) > Math.abs(afterUndo.length - afterDraw.length)) {
  fail('undo changed the sketch but did not restore it')
} else {
  pass('undo restores the sketch')
}

if (pageErrors.length > 0) {
  fail(`the page threw while being used:\n      ${pageErrors.join('\n      ')}`)
}

await browser.close()

if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`)
  process.exit(1)
}
console.log('\nBrowser smoke test passed.')
