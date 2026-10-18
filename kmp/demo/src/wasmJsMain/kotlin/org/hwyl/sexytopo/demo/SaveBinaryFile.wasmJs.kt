package org.hwyl.sexytopo.demo

import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array

private fun saveBytesThroughPage(filename: String, data: Int8Array): String =
    js(
        """{
          try {
            return window.sexytopoFiles.save(filename, data, 'application/zip');
          } catch (e) {
            return '';
          }
        }""",
    )

/**
 * The same route out as the text one, in `files.js`.
 *
 * The bytes cross into JavaScript as an `Int8Array` copy: a Kotlin `ByteArray` is not something
 * the `js(...)` bridge can pass, and a base64 string would mean encoding the whole archive into a
 * string and decoding it again on the other side for no gain. A copy rather than a view matters
 * too, since the file is written after the save dialog closes.
 */
actual fun saveBinaryFile(filename: String, bytes: ByteArray): String? =
    describeSave(saveBytesThroughPage(filename, bytes.toInt8Array()))
