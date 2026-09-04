package org.hwyl.sexytopo.demo

/**
 * Open the browser's on-screen keyboard, which will not open itself.
 *
 * Reported from a phone: tapping *New Survey* puts a cursor in the name box and no keyboard under
 * it, and the same for the label box — unless a keyboard happened to be open already when the app
 * came to the front, in which case everything works. That last detail is the whole diagnosis.
 *
 * A browser opens its keyboard when focus moves to an editable element *and* the page holds
 * transient user activation. The HTML specification grants that on `pointerup`, `touchend`, `click`
 * and `mousedown`, and pointedly not on `pointerdown` or `touchstart`. Compose Multiplatform 1.12.0
 * paints into a canvas and drives typing through a hidden `input` of its own, and focuses it from
 * the text input session — a coroutine, so a frame or more after the tap that started it, by which
 * time the browser has forgotten the tap ever happened. Its own recovery path, a `touchstart`
 * listener in `ComposeWindow.initEvents` that re-focuses that input, is on the one touch event that
 * grants no activation either. So the keyboard can be kept open and never opened, which is exactly
 * what a surveyor sees.
 *
 * This runs from [rememberOpeningFocus]'s pointer-up handler, which Compose dispatches synchronously
 * from the DOM `pointerup` — inside the activation window — and does whichever of three things the
 * browser will accept:
 *
 *  - `navigator.virtualKeyboard.show()` where it exists, which is Chromium's own way of saying this
 *    and the only one that works when the field is *already* focused;
 *  - failing that, focus Compose's hidden input if it has made one yet;
 *  - failing that, focus a decoy of our own, because on the first tap of a session Compose has not
 *    built its input yet and there is nothing else editable to focus. The keyboard comes up for the
 *    decoy, Compose's input takes the focus a moment later, and the keyboard stays up — which is
 *    the "unless it was already open" case, arranged on purpose.
 *
 * The decoy is one element for the life of the page, off the bottom of the screen and invisible, and
 * is never typed into: whatever the surveyor types arrives after Compose has taken the focus back.
 * Its 16px font is not decoration — Safari zooms the whole page in on focusing anything smaller.
 */
internal actual fun askForTheKeyboard() {
    openTheKeyboard(DECOY_ID)
}

/**
 * The decoy's id, handed to the script rather than written into it: the browser tests look the
 * element up by this name to tell it apart from the input Compose types through, and a constant
 * copied into a string of JavaScript is one nothing will ever notice drifting.
 */
internal const val DECOY_ID: String = "sexytopo-keyboard-decoy"

private fun openTheKeyboard(decoyId: String): Unit =
    js(
        """(function () {
            // Chromium's VirtualKeyboard API. Unlike a focus change this works on a field that is
            // already focused, which is the auto-focused dialog's case.
            try {
                if (navigator.virtualKeyboard && navigator.virtualKeyboard.show) {
                    navigator.virtualKeyboard.show();
                    return;
                }
            } catch (e) {
                // Not supported here, or refused. The focus routes below are the fallback.
            }

            // Compose 1.12.0 mounts its canvas, and everything beside it, inside an open shadow
            // root, which document.querySelector cannot see into - so this walks into every shadow
            // root it finds rather than assuming the light DOM. Same shape as findCanvas in
            // PinchToZoom.wasmJs.kt, and for the same reason.
            var findInput = function (root) {
                var inputs = root.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                    if (inputs[i].id !== decoyId) { return inputs[i]; }
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                    if (all[j].shadowRoot) {
                        var inShadow = findInput(all[j].shadowRoot);
                        if (inShadow) { return inShadow; }
                    }
                }
                return null;
            };

            var focusIt = function (element) {
                try {
                    element.focus({ preventScroll: true });
                } catch (e) {
                    element.focus();
                }
            };

            var input = findInput(document);
            if (input) {
                focusIt(input);
                return;
            }

            var decoy = document.getElementById(decoyId);
            if (!decoy) {
                decoy = document.createElement('input');
                decoy.id = decoyId;
                decoy.type = 'text';
                decoy.setAttribute('aria-hidden', 'true');
                decoy.setAttribute('tabindex', '-1');
                decoy.setAttribute('autocomplete', 'off');
                decoy.setAttribute('autocorrect', 'off');
                decoy.style.cssText =
                    'position:fixed;bottom:0;left:0;width:1px;height:1px;opacity:0;' +
                    'border:0;padding:0;margin:0;font-size:16px;pointer-events:none;z-index:-1;';
                document.body.appendChild(decoy);
            }
            focusIt(decoy);
        })()""",
    )
