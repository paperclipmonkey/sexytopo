// The desk half of the browser build: a wheel, a trackpad and a keyboard.
//
// Everything else in this suite runs at phone size with a finger, because that is where surveying
// happens. Both checks here came from desktop bug reports: trackpad pinch zooming the whole page
// instead of the survey, and no ctrl+z support for undo/redo.
//
// A separate script from `field.mjs`: it needs a desktop-sized window and a wheel, and every check
// here moves the viewport, which field.mjs's fixed coordinates depend on staying put.
//
//   node desktop.mjs <url> [screenshotDir]
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:8080/index.html'
const shotDir = process.argv[3] ?? 'desktop-screenshots'
mkdirSync(shotDir, { recursive: true })

const failures = []
const fail = (m) => { failures.push(m); console.error(`FAIL  ${m}`) }
const pass = (m) => console.log(`ok    ${m}`)

const launch = {}
if (process.env.CHROMIUM_PATH) launch.executablePath = process.env.CHROMIUM_PATH
const browser = await chromium.launch(launch)
// A laptop, which is what the reports came from.
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
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

// The middle of the drawing, well clear of the app bar and the toolbar, and where the pointer sits
// for every wheel gesture below: zoom is about the pointer, so keeping it in one place is what
// makes "the cave got bigger" a statement about scale rather than about where it was aimed.
const MIDDLE = [Math.round(box.width / 2), Math.round(box.height / 2)]

/**
 * The centreline on the drawing: how much of it there is, and where its middle is.
 *
 * The demo cave is drawn in the app's own pure red, and nothing else on the page is - so counting
 * those pixels measures scale (twice the pixels at a fixed line width for a leg twice as long) and
 * their mean position measures where the drawing sits. A zoom changes the count; a pan does not.
 *
 * Bounded to the sketch, with the app bar above it and the toolbar below.
 */
const centreline = async () => {
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
    let count = 0
    let sumX = 0
    let sumY = 0
    let minY = Infinity
    let maxY = -Infinity
    for (let y = top; y < bottom; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        // `leg` is 0xFF0000 and `latestLeg` the magenta 0xFF00FF; splays are salmon and stations a
        // dark red, so a red channel this high with green this low is the centreline alone.
        if (px[i] > 230 && px[i + 1] < 60 && (px[i + 2] < 60 || px[i + 2] > 230)) {
          count++
          sumX += x
          sumY += y
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    if (count === 0) return null
    return {
      count,
      x: Math.round(sumX / count),
      y: Math.round(sumY / count),
      // How tall the cave is drawn. Scales with the zoom exactly, where the pixel count only does
      // so roughly - a line has a fixed width in dp, so doubling the scale doubles its length and
      // not quite its area, and antialiasing rounds differently at either end.
      height: maxY - minY,
    }
  }, [b64, 70, Math.round(box.height) - 100])
}

// A wheel event with ctrl held is what a MacBook trackpad pinch actually is; Playwright's
// `mouse.wheel` sends a plain one, so the modifier is held down around it the way the trackpad
// holds it.
const wheelAt = async ([x, y], deltaY, { ctrl = false } = {}) => {
  await page.mouse.move(box.x + x, box.y + y)
  if (ctrl) await page.keyboard.down('Control')
  await page.mouse.wheel(0, deltaY)
  if (ctrl) await page.keyboard.up('Control')
  await page.waitForTimeout(500)
}

const started = await centreline()
if (!started) {
  fail('no centreline was found on the demo cave, so nothing below could be measured')
  await browser.close()
  process.exit(1)
}
await page.screenshot({ path: join(shotDir, 'desktop-start.png') })

// ---- a trackpad pinch zooms the survey, not the page ----------------------------------------
await wheelAt(MIDDLE, -240, { ctrl: true })
await page.screenshot({ path: join(shotDir, 'desktop-pinched-in.png') })
const zoomedIn = await centreline()

if (!zoomedIn) {
  fail('the centreline vanished when the drawing was zoomed in')
} else if (!(zoomedIn.count > started.count * 1.15)) {
  fail(`ctrl and scroll did not zoom the survey in (${started.count} then ${zoomedIn.count} pixels)`)
} else {
  pass(`a trackpad pinch zooms the survey in (${started.count} to ${zoomedIn.count} pixels of centreline)`)
}

// ---- and the browser does not zoom the page as well -------------------------------------------
// The actual complaint was "pinch to zoom zooms the whole page rather than the survey" - the app
// has to `preventDefault` on the wheel event, from a listener registered `passive: false`.
//
// Checked on the event rather than the screen. Reading `devicePixelRatio` after a ctrl-scroll and
// asserting it hasn't moved **passes with the whole fix deleted**, because Playwright's wheel goes
// in through the DevTools protocol and never reaches Chromium's own page-zoom path. Dispatching a
// real WheelEvent from inside the page and reading `defaultPrevented` back tests the mechanism
// itself.
const pinchPrevented = await page.evaluate(() => {
  const canvas = document.querySelector('canvas')
  const e = new WheelEvent('wheel', {
    deltaY: -100, ctrlKey: true, bubbles: true, cancelable: true,
  })
  canvas.dispatchEvent(e)
  return e.defaultPrevented
})
if (!pinchPrevented) {
  fail('the app lets the browser have the pinch, so the page will zoom instead of the survey')
} else {
  pass('and the browser is told to leave the page alone')
}

// ---- and back out again ---------------------------------------------------------------------
// The half that says the arithmetic is a scale rather than a step: a notch out has to undo a notch
// in, or the drawing walks away from you over a working session.
await wheelAt(MIDDLE, 240, { ctrl: true })
const zoomedBack = await centreline()
if (!zoomedBack) {
  fail('the centreline vanished when the drawing was zoomed back out')
} else if (Math.abs(zoomedBack.count - started.count) > started.count * 0.06) {
  fail(
    `scrolling back out did not return to the scale it started at ` +
      `(${started.count} then ${zoomedBack.count} pixels)`,
  )
} else {
  pass('and scrolling back out returns to the scale it started at')
}

// ---- a plain two-finger scroll slides the paper ----------------------------------------------
// The other half of a trackpad. Checked as a *move* rather than a scale, which is the distinction
// the two branches of the handler exist for: the same wheel event means something different with
// the modifier held.
//
// Zoomed out first, and that is not decoration. The app opens fitted, so the cave fills the window
// and *any* pan pushes some of it off the edge - which drops the pixel count exactly as a zoom out
// would, and leaves the count unable to tell the two apart. The first attempt at this check read
// 2092 pixels then 595 and reported a scale change that had not happened. At 40% the whole cave
// has room to move without touching a wall.
await wheelAt(MIDDLE, 600, { ctrl: true })
await page.waitForTimeout(400)
const beforePan = await centreline()
await wheelAt(MIDDLE, 100)
await page.screenshot({ path: join(shotDir, 'desktop-scrolled.png') })
const panned = await centreline()
if (!panned || !beforePan) {
  fail('the centreline vanished when the drawing was scrolled')
} else if (Math.abs(beforePan.y - panned.y - 100) > 25) {
  fail(`scrolling 100 pixels down moved the drawing ${beforePan.y - panned.y} up, not 100`)
} else if (Math.abs(panned.x - beforePan.x) > 8) {
  fail(`scrolling down moved the drawing sideways as well (x ${beforePan.x} then ${panned.x})`)
} else if (Math.abs(panned.count - beforePan.count) > beforePan.count * 0.08) {
  fail(`scrolling changed the scale as well as the position (${beforePan.count} then ${panned.count})`)
} else {
  pass(`a plain scroll slides the drawing without resizing it (y ${beforePan.y} to ${panned.y})`)
}

// ---- including in Safari, which does not send a wheel at all ----------------------------------
// Safari delivers a trackpad pinch as its own non-standard `gesturestart`/`gesturechange`, which
// no Compose target reads, so the browser host turns them into the ctrl-wheel the rest of this
// expects. That path cannot be exercised in Safari here - but it can be *driven*, because the shim
// reads nothing off the event but `scale` and the pointer position, and those go on a plain one.
//
// Checked end to end - dispatch the gesture, measure the cave - rather than by reading the
// synthesized wheel's deltaY and comparing it against the formula. The formula is the thing under
// test; asserting it against itself would pass whatever number the shim invented. Done here, with
// the drawing at forty percent, because a pinch has to have somewhere to grow into.
const beforeSafari = await centreline()
const safariPrevented = await page.evaluate(() => {
  const gesture = (type, scale) => {
    const e = new Event(type, { bubbles: true, cancelable: true })
    e.scale = scale
    e.clientX = 640
    e.clientY = 400
    window.dispatchEvent(e)
    return e.defaultPrevented
  }
  const started = gesture('gesturestart', 1)
  const changed = gesture('gesturechange', 1.6)
  gesture('gestureend', 1.6)
  return started && changed
})
await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'desktop-safari-pinch.png') })
const afterSafari = await centreline()
if (!safariPrevented) {
  fail("Safari's own pinch events are left to the browser, so Safari will zoom the page")
} else if (!afterSafari || !beforeSafari) {
  fail('the centreline vanished during a Safari pinch')
} else {
  // A pinch to 1.6 has to zoom the drawing by 1.6, not by "some amount in the right direction":
  // the first version of the shim was out by a factor of six hundred and sixty-six and still
  // zoomed in, by six parts in a thousand. Measured off the cave's drawn height, which scales with
  // the zoom exactly.
  const grew = afterSafari.height / beforeSafari.height
  if (Math.abs(grew - 1.6) > 0.1) {
    fail(`a Safari pinch to 1.6 zoomed the drawing by ${grew.toFixed(2)}, not by about 1.6`)
  } else {
    pass(`a Safari pinch zooms the survey by as much as the fingers moved (x${grew.toFixed(2)})`)
  }
}

// ---- ctrl+z takes back the last stroke --------------------------------------------------------
// Drawn with the pencil, on the demo cave, which is never saved - so this leaves nothing behind.
const strokeInk = async () => {
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
    let ink = 0
    for (let y = top; y < bottom; y++) {
      for (let x = 0; x < c.width; x++) {
        const i = (y * c.width + x) * 4
        // The default brush is black, and the plan is drawn on near-white with a grey grid.
        if (px[i] < 60 && px[i + 1] < 60 && px[i + 2] < 60) ink++
      }
    }
    return ink
  }, [b64, 70, Math.round(box.height) - 100])
}

// The sketch toolbar is nine equal columns along the bottom; the second is the pencil.
const toolColumn = box.width / 9
await at(toolColumn * 1.5, box.height - 20)
await page.waitForTimeout(400)

const blank = await strokeInk()
await page.mouse.move(box.x + 300, box.y + 300)
await page.mouse.down()
for (const step of [1, 2, 3, 4, 5, 6]) {
  await page.mouse.move(box.x + 300 + step * 40, box.y + 300 + step * 12)
  await page.waitForTimeout(30)
}
await page.mouse.up()
await page.waitForTimeout(600)
await page.screenshot({ path: join(shotDir, 'desktop-drawn.png') })
const drawn = await strokeInk()

if (!(drawn > blank + 100)) {
  fail(`the pencil drew nothing at desktop size (${blank} then ${drawn} pixels of ink)`)
} else {
  pass(`the pencil draws with a mouse held down (${blank} to ${drawn} pixels of ink)`)
}

await page.keyboard.press('Control+z')
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'desktop-undone.png') })
const undone = await strokeInk()
if (Math.abs(undone - blank) > 40) {
  fail(`ctrl+z did not take the stroke back (${blank} blank, ${drawn} drawn, ${undone} after undo)`)
} else {
  pass('ctrl+z takes back the last stroke')
}

// ---- and ctrl+shift+z puts it back ------------------------------------------------------------
await page.keyboard.press('Control+Shift+z')
await page.waitForTimeout(700)
await page.screenshot({ path: join(shotDir, 'desktop-redone.png') })
const redone = await strokeInk()
if (Math.abs(redone - drawn) > 40) {
  fail(`ctrl+shift+z did not put the stroke back (${drawn} drawn, ${redone} after redo)`)
} else {
  pass('and ctrl+shift+z puts it back')
}

// Ctrl+Y, because half the world reaches for that one instead.
await page.keyboard.press('Control+z')
await page.waitForTimeout(600)
await page.keyboard.press('Control+y')
await page.waitForTimeout(700)
const redoneAgain = await strokeInk()
if (Math.abs(redoneAgain - drawn) > 40) {
  fail(`ctrl+y did not redo (${drawn} drawn, ${redoneAgain} after ctrl+y)`)
} else {
  pass('and so does ctrl+y')
}

// An undo with nothing left on the stack must not reach the browser, and must not throw.
for (let i = 0; i < 6; i++) {
  await page.keyboard.press('Control+z')
  await page.waitForTimeout(120)
}
await page.waitForTimeout(600)
const emptied = await strokeInk()
if (Math.abs(emptied - blank) > 40) {
  fail(`undoing past the start left the drawing changed (${blank} blank, ${emptied} after)`)
} else {
  pass('undoing past the start of the stack does nothing rather than something')
}

// ---- the 3D view has its own wheel gestures too ------------------------------------------------
// Reported from a MacBook: the 3D camera's touch loop counts fingers off `pressed` pointer changes,
// and a trackpad's two fingers never produce one - they are a `wheel` event, ctrl held for a pinch -
// so before this fix pinching and panning the cave from a desk did nothing at all.
//
// Reached through the app's own overflow menu - View, then 3D - measured off the rendered popup
// rather than a fixed pixel, the way `field.mjs`'s own menu lookups are: this file finishes at a
// different width, and only a position read off the actual menu stays right regardless of it.
const MENU_SURFACE = [243, 237, 247]
const overflowButton = () => [box.width - 16, 26]
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
  }, [b64, MENU_SURFACE])
}
const menuRowAt = async (index, rows, x) => {
  const menu = await menuBox()
  if (menu === null) throw new Error('no menu is open')
  const rowHeight = (menu.bottom - menu.top) / rows
  return [x, Math.round(menu.top + (index + 0.5) * rowHeight)]
}
const menuMiddle = () => box.width - 116

// The overflow menu's own top page - File, View, Instrument, Tools, Settings, Help - then View's
// own four rows below a Back row: Demo cave, Trip, 3D, Stats.
await at(...overflowButton()); await page.waitForTimeout(600)
await at(...(await menuRowAt(1, 6, menuMiddle()))); await page.waitForTimeout(500)
await at(...(await menuRowAt(3, 5, menuMiddle()))); await page.waitForTimeout(1400)
await page.screenshot({ path: join(shotDir, 'desktop-3d.png') })

// The 3D renderer's own red, a darker shade than the 2D sketch's, so `centreline` above would not
// see it - counted the same way `field.mjs` counts a leg pixel there.
const legPixels3D = async () => {
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
    let red = 0
    let sumX = 0
    let sumY = 0
    for (let i = 0; i < px.length; i += 4) {
      if (px[i] > 120 && px[i + 1] < 110 && px[i + 2] < 110) {
        red++
        const p = i / 4
        sumX += p % c.width
        sumY += Math.floor(p / c.width)
      }
    }
    return red === 0 ? null : { count: red, x: Math.round(sumX / red), y: Math.round(sumY / red) }
  }, b64)
}

const before3D = await legPixels3D()
if (!before3D) {
  fail('the 3D view drew nothing, so the wheel checks below could not run')
} else {
  // ---- ctrl+wheel zooms the camera in, the same pinch-out the 2D canvas reads ------------------
  await wheelAt([Math.round(box.width / 2), Math.round(box.height / 2)], -240, { ctrl: true })
  const zoomedIn3D = await legPixels3D()
  if (!zoomedIn3D) {
    fail('the centreline vanished when the 3D view was zoomed in')
  } else if (!(zoomedIn3D.count > before3D.count * 1.15)) {
    fail(`ctrl and scroll did not zoom the 3D view in (${before3D.count} then ${zoomedIn3D.count} pixels)`)
  } else {
    pass(`a trackpad pinch zooms the 3D view in (${before3D.count} to ${zoomedIn3D.count} pixels)`)
  }

  // ---- and a plain scroll pans it, which used to do nothing at all ------------------------------
  await wheelAt([Math.round(box.width / 2), Math.round(box.height / 2)], 150)
  await page.screenshot({ path: join(shotDir, 'desktop-3d-panned.png') })
  const panned3D = await legPixels3D()
  if (!panned3D) {
    fail('the centreline vanished when the 3D view was scrolled')
  } else if (Math.abs(panned3D.y - zoomedIn3D.y) < 15 && Math.abs(panned3D.x - zoomedIn3D.x) < 15) {
    fail(`a plain scroll did not move the 3D view (${zoomedIn3D.x},${zoomedIn3D.y} then ${panned3D.x},${panned3D.y})`)
  } else {
    pass(`a plain scroll pans the 3D view (${zoomedIn3D.x},${zoomedIn3D.y} to ${panned3D.x},${panned3D.y})`)
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
console.log('\nDesktop input test passed.')
