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
            input.accept = '.json,.svx,.th,.txt,.top,application/json,text/plain';
            input.style.display = 'none';
            input.addEventListener('change', function () {
              var file = input.files && input.files[0];
              if (!file) { input.remove(); return; }
              // PocketTopo's .top is binary, and localStorage holds strings: read as text and every
              // byte that is not valid UTF-8 comes back as a replacement character, which for a
              // binary format moves a length prefix and ruins everything after it. So a .top goes
              // in base64 under the store's binary key prefix, and BrowserFileStore.readBytes
              // decodes it. Everything else this app imports really is text.
              var binary = /\.top$/i.test(file.name);
              var reader = new FileReader();
              reader.onload = function () {
                try {
                  if (binary) {
                    // readAsDataURL gives "data:<type>;base64,<payload>"; only the payload is wanted.
                    var payload = String(reader.result);
                    localStorage.setItem(
                      'sexytopo:b:' + file.name,
                      payload.slice(payload.indexOf(',') + 1),
                    );
                  } else {
                    localStorage.setItem('sexytopo:f:' + file.name, reader.result);
                  }
                } catch (e) {
                  // Quota, or storage disabled. The import list simply will not gain an entry,
                  // which is what the dialog already says when there is nothing to import.
                }
                input.remove();
              };
              if (binary) { reader.readAsDataURL(file); } else { reader.readAsText(file); }
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
