package org.hwyl.sexytopo.demo

/**
 * A hidden `<input type="file">`, which is the only file chooser a web page has.
 *
 * The chosen file is written straight into the app's own storage under the `BrowserFileStore` key
 * prefix, so from that point on the browser looks exactly like iOS with a file dropped into the
 * Files app, and one shared code path imports both. On iOS Safari the chooser offers iCloud Drive
 * and anything AirDropped, so the browser build can import too.
 */
private fun openPicker(): Boolean =
    js(
        """{
          try {
            var input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json,.svx,.th,.txt,application/json,text/plain';
            input.style.display = 'none';
            input.addEventListener('change', function () {
              var file = input.files && input.files[0];
              if (!file) { input.remove(); return; }
              var reader = new FileReader();
              reader.onload = function () {
                try {
                  localStorage.setItem('sexytopo:f:' + file.name, reader.result);
                } catch (e) {
                  // Quota, or storage disabled. The import list simply will not gain an entry,
                  // which is what the dialog already says when there is nothing to import.
                }
                input.remove();
              };
              reader.readAsText(file);
            });
            document.body.appendChild(input);
            input.click();
            return true;
          } catch (e) {
            return false;
          }
        }""",
    )

actual fun canPickFiles(): Boolean = true

actual fun pickSurveyFile() {
    openPicker()
}
