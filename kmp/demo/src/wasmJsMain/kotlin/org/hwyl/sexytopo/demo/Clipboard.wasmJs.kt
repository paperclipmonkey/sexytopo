package org.hwyl.sexytopo.demo

/**
 * Writes to the clipboard from the browser.
 *
 * `navigator.clipboard.writeText` is asynchronous and permission-gated; this fires it and reports
 * that it was *attempted*, because waiting on a promise would mean making the whole call chain
 * suspending for a button that either works or does not. Safari grants it inside a user gesture,
 * which a tap on Copy is.
 *
 * The `document.execCommand` fallback is for older iOS Safari, where the async API is unavailable
 * outside a secure context.
 */
private fun writeClipboard(text: String): Boolean =
    js(
        """{
          try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
              navigator.clipboard.writeText(text);
              return true;
            }
            var a = document.createElement('textarea');
            a.value = text;
            a.setAttribute('readonly', '');
            a.style.position = 'absolute';
            a.style.left = '-9999px';
            document.body.appendChild(a);
            a.select();
            var ok = document.execCommand('copy');
            document.body.removeChild(a);
            return ok;
          } catch (e) {
            return false;
          }
        }""",
    )

actual fun copyToClipboard(text: String): Boolean = writeClipboard(text)
