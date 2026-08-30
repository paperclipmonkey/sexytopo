package org.hwyl.sexytopo.demo

/**
 * A blob and a synthetic click on a download link, which is the only file-saving mechanism a web
 * page has.
 *
 * On iOS Safari this opens the share sheet rather than writing to a Downloads folder, so the
 * surveyor picks *Save to Files* — which lands the file exactly where the native build would put
 * it. The wording below has to cover both, hence "your downloads or Files".
 */
private fun downloadFile(filename: String, text: String): Boolean =
    js(
        """(function () {
            try {
                var blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
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

actual fun saveTextFile(filename: String, text: String): String? =
    if (downloadFile(filename, text)) "your downloads or Files" else null
