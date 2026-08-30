package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore

/**
 * Not yet persistent on this platform.
 *
 * The browser host is the one that has to survive being closed, because that is the build a caver
 * can install on an iPhone today. A real implementation here means `NSFileManager` on iOS and the
 * Storage Access Framework on Android - see WP3 in the plan - and both need a device to test on.
 * An in-memory store keeps the app working meanwhile rather than pretending to save.
 */
actual fun platformFileStore(): FileStore = InMemoryFileStore()
