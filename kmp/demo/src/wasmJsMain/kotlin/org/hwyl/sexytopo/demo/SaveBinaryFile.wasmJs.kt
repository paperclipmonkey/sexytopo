package org.hwyl.sexytopo.demo

import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array

private fun downloadBytes(filename: String, data: Int8Array): Boolean =
    js(
        """(function () {
            try {
                var blob = new Blob([data], { type: 'application/zip' });
                var url = URL.createObjectURL(blob);
                var link = document.createElement('a');
                link.href = url;
                link.download = filename;
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
                return true;
            } catch (e) {
                return false;
            }
        })()""",
    )

/**
 * A blob and a synthetic click, as the text one does — the only way a page hands a file to the
 * person looking at it.
 *
 * The bytes cross into JavaScript as an `Int8Array` view: a Kotlin `ByteArray` is not something the
 * `js(...)` bridge can pass, and a base64 string would mean encoding the whole archive into a
 * string and decoding it again on the other side for no gain.
 */
actual fun saveBinaryFile(filename: String, bytes: ByteArray): String? =
    if (downloadBytes(filename, bytes.toInt8Array())) "your downloads or Files" else null
