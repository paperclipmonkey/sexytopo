package org.hwyl.sexytopo.demo

/**
 * Stop the *browser* taking a trackpad pinch, so the app can have it.
 *
 * Reported from a MacBook: "pinch to zoom zooms the whole page rather than the survey". A pinch on
 * a Mac trackpad is not a touch gesture as far as the page is concerned - Chrome and Firefox
 * deliver it as a `wheel` event with `ctrlKey` set, which the browser also treats as its own
 * page-zoom shortcut. Compose sees the event either way and [SurveyCanvas] now zooms the drawing
 * with it; what it cannot do from inside is stop the page zooming as well, and a survey that
 * shrinks while the whole app grows around it is worse than neither.
 *
 * So one listener, calling `preventDefault` on exactly the events that are a pinch. It has to be
 * registered `passive: false` or Chrome ignores the call: wheel listeners on the window are
 * passive by default precisely so that page scrolling cannot be blocked by accident.
 *
 * Safari is the odd one out and sends `gesturestart`/`gesturechange`/`gestureend` instead, which
 * are not standard and which no Compose target reads. Those are prevented too, and turned into the
 * ctrl-wheel event the rest of this expects, so a pinch means the same thing in every browser.
 * `scale` is a ratio against the start of the gesture, and the wheel path turns a scroll of `d`
 * pixels into a zoom of `exp(-d * k)`. So `-ln(scale) / k` is the scroll that means exactly the
 * pinch the fingers made, and the drawing follows them rather than approximating them.
 * [wheelPixelsPerLogScale] is `1 / k`, handed in from [ZOOM_PER_SCROLLED_PIXEL] rather than
 * written down again here, because a constant copied into a string of JavaScript is one nothing
 * will ever notice drifting.
 */
internal fun keepPinchesInsideTheApp(wheelPixelsPerLogScale: Double): Unit =
    js(
        """(function () {
            window.addEventListener('wheel', function (e) {
                if (e.ctrlKey) { e.preventDefault(); }
            }, { passive: false, capture: true });

            var startScale = 1;
            var pinch = function (e) {
                e.preventDefault();
                var canvas = document.querySelector('canvas');
                if (!canvas || !e.scale || !startScale) { return; }
                var step = Math.log(e.scale / startScale) * wheelPixelsPerLogScale;
                startScale = e.scale;
                if (!isFinite(step) || step === 0) { return; }
                canvas.dispatchEvent(new WheelEvent('wheel', {
                    clientX: e.clientX,
                    clientY: e.clientY,
                    deltaY: -step,
                    ctrlKey: true,
                    bubbles: true,
                    cancelable: true,
                }));
            };
            window.addEventListener('gesturestart', function (e) {
                e.preventDefault();
                startScale = e.scale || 1;
            }, { passive: false });
            window.addEventListener('gesturechange', pinch, { passive: false });
            window.addEventListener('gestureend', function (e) {
                e.preventDefault();
                startScale = 1;
            }, { passive: false });
        })()"""
    )
