package org.hwyl.sexytopo.demo

private fun saveThroughPage(filename: String, text: String): String =
    js(
        """{
          try {
            return window.sexytopoFiles.save(filename, text, 'text/plain;charset=utf-8');
          } catch (e) {
            return '';
          }
        }""",
    )

/**
 * Hands the file to `files.js`, which asks where to put it where the browser can (Chrome and Edge
 * on a desktop) and downloads it everywhere else.
 *
 * On iOS Safari a download opens the share sheet rather than writing to a Downloads folder, so the
 * surveyor picks *Save to Files* — which lands the file exactly where the native build would put
 * it. The wording below has to cover both, hence "your downloads or Files".
 */
actual fun saveTextFile(filename: String, text: String): String? =
    describeSave(saveThroughPage(filename, text))

/**
 * What the export screen says after "Saved to". Written before the surveyor has answered the save
 * dialog, because the dialog is asynchronous and this is not; a cancelled dialog writes nothing.
 */
internal fun describeSave(how: String): String? =
    when (how) {
        "chosen" -> "the place you chose"
        "download" -> "your downloads or Files"
        else -> null
    }
