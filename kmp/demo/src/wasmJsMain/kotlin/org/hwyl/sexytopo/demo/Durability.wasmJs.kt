package org.hwyl.sexytopo.demo

/**
 * Ask Chrome — or whichever browser this is — not to reclaim the surveys.
 *
 * Called once at startup. `persist()` returns a promise and Kotlin/Wasm's `js()` interop is one-way
 * (JavaScript cannot call back into Kotlin), so the outcome is parked on a global for
 * [durabilityWarning] to read on a later frame, exactly as [WebBluetoothTransport] parks its
 * events. Until it resolves the state is "asking", which warns about nothing: the honest answer for
 * a fraction of a second is "we do not know yet", and flashing a warning that then disappears is
 * how a real one gets ignored.
 */
actual fun requestDurableStorage() {
    askForDurableStorage()
}

actual fun durabilityWarning(): String? =
    when (durableStorageState()) {
        // Granted, or not yet decided. Nothing useful to say in either case.
        "persisted", "asking" -> null

        "unsupported" ->
            "This browser cannot promise to keep saved surveys. Export anything you need to keep."

        // "evictable" — asked and refused — and "unknown", where the call itself failed. Both mean
        // the same thing to a surveyor, so they get the same sentence rather than a distinction
        // they cannot act on differently.
        else ->
            "The browser has not promised to keep saved surveys: clearing site data or a storage " +
                "squeeze can remove them. Export anything you need to keep."
    }

/**
 * Requests persistence at most once per page load, and reports what is known so far.
 *
 * Asking twice is harmless per the specification, but a browser that prompts (Firefox) would prompt
 * again, so the guard is not only tidiness.
 */
private fun askForDurableStorage(): String =
    js(
        """{
          try {
            var S = (globalThis.__sexytopoStorage = globalThis.__sexytopoStorage || {});
            if (S.asked) return S.state || 'asking';
            S.asked = true;
            if (typeof navigator === 'undefined' || !navigator.storage || !navigator.storage.persist) {
              S.state = 'unsupported';
              return S.state;
            }
            S.state = 'asking';
            navigator.storage
              .persist()
              .then(function (granted) { S.state = granted ? 'persisted' : 'evictable'; })
              .catch(function () { S.state = 'unknown'; });
            return S.state;
          } catch (e) {
            return 'unknown';
          }
        }""",
    )

private fun durableStorageState(): String =
    js(
        """{
          var S = globalThis.__sexytopoStorage;
          return (S && S.state) || 'asking';
        }""",
    )
