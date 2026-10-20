// Files coming into the app from the rest of the computer or phone, and going back out to it.
//
// A web page has no folder of its own the system file manager can see, which is how the iOS and
// desktop builds take a survey in. So everything that hands this page a file - the chooser, a file
// dragged onto the window, or the operating system's "Open with" on an installed app - ends in the
// same place: the file is written into the app's own storage root under BrowserFileStore's keys,
// and from there one shared code path imports it exactly as iOS imports a file dropped into Files.
//
// Plain JavaScript in its own file rather than inside Kotlin js(...) blocks because two of those
// routes (the drop and the launch queue) have to be listening before the WebAssembly module has
// even downloaded, and because unzipping is long enough to deserve a file of its own.
;(function () {
  var PREFIX = 'sexytopo:'

  // What has arrived since Kotlin last asked. Polled rather than called back into, so a file the
  // operating system hands over before the module has loaded is simply waiting when it starts.
  var arrived = []

  // PocketTopo's .top is binary, and localStorage holds strings: read as text and every byte that
  // is not valid UTF-8 comes back as a replacement character, which for a binary format moves a
  // length prefix and ruins everything after it. So a .top goes in base64 under the store's binary
  // key prefix, and BrowserFileStore.readBytes decodes it. Everything else this app imports really
  // is text.
  function isBinary(name) {
    return /\.top$/i.test(name)
  }

  function base64(bytes) {
    var chunks = []
    // In slices: String.fromCharCode.apply over a whole file overflows the argument limit.
    for (var i = 0; i < bytes.length; i += 0x8000) {
      chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)))
    }
    return btoa(chunks.join(''))
  }

  function baseName(path) {
    return path.split(/[\\/]/).pop()
  }

  function store(name, bytes) {
    name = baseName(name)
    if (!name) return
    try {
      // Only one of the two keys, so a file picked a second time as the other kind cannot be
      // shadowed by what was there before.
      if (isBinary(name)) {
        localStorage.removeItem(PREFIX + 'f:' + name)
        localStorage.setItem(PREFIX + 'b:' + name, base64(bytes))
      } else {
        localStorage.removeItem(PREFIX + 'b:' + name)
        localStorage.setItem(PREFIX + 'f:' + name, new TextDecoder('utf-8').decode(bytes))
      }
      arrived.push(name)
    } catch (e) {
      // Quota, or storage disabled. The import list simply will not gain an entry, which is what
      // the dialog already says when there is nothing to import.
      console.warn('could not keep ' + name + ':', e)
    }
  }

  // ---- unzipping ---------------------------------------------------------------------------
  //
  // A survey travels as a zip far more often than as four loose files - it is what this app's own
  // Share survey writes - so a zip is opened and its files put at the root side by side, which is
  // where the importer already looks for a data file's drawings. Folders inside it are flattened
  // for the same reason: zipping a survey folder in Finder or Explorer puts everything one level
  // down.
  //
  // Only what real survey zips use: stored entries, which this app writes, and deflated ones, which
  // every desktop zip tool writes, inflated by the browser's own DecompressionStream. No Zip64 and
  // no encryption; an entry this cannot read is skipped rather than failing the whole archive.

  function u16(view, at) {
    return view.getUint16(at, true)
  }

  function u32(view, at) {
    return view.getUint32(at, true)
  }

  function inflate(bytes) {
    if (typeof DecompressionStream === 'undefined') {
      return Promise.reject(new Error('this browser cannot inflate a zip entry'))
    }
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
    return new Response(stream).arrayBuffer().then(function (buffer) {
      return new Uint8Array(buffer)
    })
  }

  function unzip(bytes) {
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    // The end-of-central-directory record is the last thing in the file, followed only by a
    // comment of up to 65535 bytes.
    var end = -1
    for (var i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 0xffff); i--) {
      if (u32(view, i) === 0x06054b50) {
        end = i
        break
      }
    }
    if (end < 0) return Promise.reject(new Error('not a zip file'))

    var count = u16(view, end + 10)
    var at = u32(view, end + 16)
    var entries = []
    for (var n = 0; n < count && u32(view, at) === 0x02014b50; n++) {
      var method = u16(view, at + 10)
      var size = u32(view, at + 20)
      var nameLength = u16(view, at + 28)
      var extraLength = u16(view, at + 30)
      var commentLength = u16(view, at + 32)
      var local = u32(view, at + 42)
      var name = new TextDecoder('utf-8').decode(bytes.subarray(at + 46, at + 46 + nameLength))
      at += 46 + nameLength + extraLength + commentLength

      // Directories, and the resource forks macOS adds to every zip it makes.
      if (/\/$/.test(name) || /(^|\/)__MACOSX\//.test(name) || /(^|\/)\._/.test(name)) continue

      // The local header's own name and extra field can differ in length from the central
      // directory's, so the data starts wherever the local header says it does.
      var start = local + 30 + u16(view, local + 26) + u16(view, local + 28)
      var data = bytes.subarray(start, start + size)
      if (method === 0) {
        entries.push(Promise.resolve({ name: name, bytes: data }))
      } else if (method === 8) {
        entries.push(
          inflate(data).then(
            function (name) {
              return function (inflated) {
                return { name: name, bytes: inflated }
              }
            }(name),
          ),
        )
      }
    }
    return Promise.all(
      entries.map(function (entry) {
        return entry.catch(function (e) {
          console.warn('skipped a zip entry:', e)
          return null
        })
      }),
    ).then(function (all) {
      return all.filter(Boolean)
    })
  }

  // ---- taking files in ---------------------------------------------------------------------

  function accept(files) {
    return Promise.all(
      Array.prototype.map.call(files, function (file) {
        return file
          .arrayBuffer()
          .then(function (buffer) {
            var bytes = new Uint8Array(buffer)
            if (/\.zip$/i.test(file.name)) {
              return unzip(bytes).then(function (entries) {
                entries.forEach(function (entry) {
                  store(entry.name, entry.bytes)
                })
              })
            }
            store(file.name, bytes)
          })
          .catch(function (e) {
            console.warn('could not read ' + file.name + ':', e)
          })
      }),
    )
  }

  // Several at once, because a SexyTopo survey is four files and taking one of them left its
  // drawings behind, a Therion project is a .th with its .xvi tracings beside it, and a Survex
  // survey keeps its extended elevation in a .espec beside the .svx.
  function pick() {
    var input = document.createElement('input')
    input.type = 'file'
    input.multiple = true
    input.accept =
      '.zip,.json,.svx,.espec,.th,.xvi,.txt,.top,application/zip,application/json,text/plain'
    input.style.display = 'none'
    var done = function () {
      input.remove()
    }
    input.addEventListener('change', function () {
      accept(input.files || []).then(done, done)
    })
    input.addEventListener('cancel', done)
    document.body.appendChild(input)
    input.click()
    return true
  }

  // A file dragged onto the window from the desktop or a file manager. Only drags that carry files
  // are claimed, so nothing else the page might one day accept by dragging is affected.
  function carriesFiles(event) {
    var types = event.dataTransfer && event.dataTransfer.types
    return !!types && Array.prototype.indexOf.call(types, 'Files') >= 0
  }
  window.addEventListener('dragover', function (event) {
    if (!carriesFiles(event)) return
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
  })
  window.addEventListener('drop', function (event) {
    if (!carriesFiles(event)) return
    event.preventDefault()
    accept(event.dataTransfer.files)
  })

  // "Open with SexyTopo" from the operating system, for the app installed from Chrome or Edge.
  // The manifest's file_handlers says which files; this is where they are handed over.
  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(function (params) {
      if (!params.files || params.files.length === 0) return
      Promise.all(
        params.files.map(function (handle) {
          return handle.getFile()
        }),
      ).then(accept, function (e) {
        console.warn('could not open what the system handed over:', e)
      })
    })
  }

  function takeArrived() {
    var names = arrived
    arrived = []
    return names.join('\n')
  }

  // ---- handing files back --------------------------------------------------------------------
  //
  // Where the browser can ask the surveyor where to put a file (Chrome and Edge on a desktop), it
  // does: one file gets a save dialog, several get a folder chooser, so a Therion export with its
  // companions is one question rather than five. Everywhere else - Safari, Firefox, every phone -
  // it is a blob and a synthetic click on a download link, which on iOS opens the share sheet and
  // its Save to Files.
  //
  // The export screen saves a format's companions one call after another inside the same tap, so
  // calls are collected and written together a microtask later: a second picker opened while the
  // first is still up is refused, and the tap that allows the first has gone by the time it closes.

  var pending = []

  function download(file) {
    try {
      var blob = new Blob([file.data], { type: file.type })
      var url = URL.createObjectURL(blob)
      var link = document.createElement('a')
      link.href = url
      link.download = baseName(file.name)
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(function () {
        URL.revokeObjectURL(url)
      }, 10000)
    } catch (e) {
      console.warn('could not download ' + file.name + ':', e)
    }
  }

  function writeTo(handle, data) {
    return handle.createWritable().then(function (writable) {
      return writable.write(data).then(function () {
        return writable.close()
      })
    })
  }

  // Into a folder, making any folder a name asks for - the Therion .xvi can be written into one
  // the .th2 names.
  function writeInto(directory, file) {
    var parts = file.name.split('/').filter(Boolean)
    var leaf = parts.pop()
    var reaching = Promise.resolve(directory)
    parts.forEach(function (part) {
      reaching = reaching.then(function (dir) {
        return dir.getDirectoryHandle(part, { create: true })
      })
    })
    return reaching
      .then(function (dir) {
        return dir.getFileHandle(leaf, { create: true })
      })
      .then(function (handle) {
        return writeTo(handle, file.data)
      })
  }

  function flush() {
    var files = pending
    pending = []
    var asking
    if (files.length === 1 && window.showSaveFilePicker) {
      asking = window
        .showSaveFilePicker({ suggestedName: baseName(files[0].name), id: 'sexytopo-save' })
        .then(function (handle) {
          return writeTo(handle, files[0].data)
        })
    } else if (files.length > 1 && window.showDirectoryPicker) {
      asking = window
        .showDirectoryPicker({ id: 'sexytopo-save', mode: 'readwrite' })
        .then(function (directory) {
          return files.reduce(function (previous, file) {
            return previous.then(function () {
              return writeInto(directory, file)
            })
          }, Promise.resolve())
        })
    } else {
      files.forEach(download)
      return
    }
    asking.catch(function (e) {
      // Cancelled is the surveyor's answer, not a failure. Anything else - the page framed by
      // another site, a folder the system will not let a page write to - still gets the file out.
      if (e && e.name === 'AbortError') return
      console.warn('could not save where asked, downloading instead:', e)
      files.forEach(download)
    })
  }

  // Says how the file is going out: 'chosen' when the surveyor will be asked where, 'download'
  // when it goes to the browser's downloads, '' if it could not be started at all.
  function save(name, data, type) {
    try {
      pending.push({ name: name, data: data, type: type })
      if (pending.length === 1) queueMicrotask(flush)
      return window.showSaveFilePicker && window.showDirectoryPicker ? 'chosen' : 'download'
    } catch (e) {
      return ''
    }
  }

  window.sexytopoFiles = { pick: pick, takeArrived: takeArrived, save: save }
})()
