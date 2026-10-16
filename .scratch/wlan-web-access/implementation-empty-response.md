# WLAN web access: why the browser got an empty response

Branch `main`, based on `a33ddfa`. The human reports that the outward listener
accepts a TCP connection on `10.215.180.89:8788` and then sends nothing:
`openssl s_client` finds no peer certificate, and the browser shows
`ERR_EMPTY_RESPONSE`. The hotspot itself works, so this is not routing.

## What closes the connection, and why

The TLS front (`WebTlsFront`) is the only listener on the network. It accepted
the TCP connection, then the handshake failed before any certificate was sent, so
the browser had nothing to read. The cause is the key manager, not the bind, the
displayed address or the `Host` check.

`WebCertificateStore` generates the private key into `AndroidKeyStore` under the
alias `pi-remote-web-access` and writes the assembled certificate to
`filesDir/web-access/cert.der`. The certificate is never installed into the
keystore entry. That is a deliberate design point (the certificate is public and
belongs in app storage; the key stays in the keystore), but it leaves the
keystore entry as a bare key with no certificate chain.

`WebTlsFront.start` built its `SSLContext` with

    KeyManagerFactory.getInstance(default).init(keyStore, null)

JSSE's X509 key manager builds its list of server aliases by reading the
certificate chain from the keystore (`KeyStore.getCertificateChain`). With the
chain null it finds no usable alias, so `chooseServerAlias` returns null, the
server sends no Certificate message, and the handshake ends. The front then
closed the socket. The same shape makes the failure look like "accepts and then
hangs up" rather than a plain listener crash.

Proven off-device, because the failure is not Android-specific, only the key
source is. A keystore that returns a key and no chain (the AndroidKeyStore shape)
makes the default manager offer nothing:

    algo=SunX509 withChain=true  chooseServerAlias=a
    algo=SunX509 withChain=false chooseServerAlias=null

Reintroducing the old `KeyManagerFactory` line as a mutation makes the new
regression test fail with the on-device symptom class:

    SSLHandshakeException: (handshake_failure) Received fatal alert: handshake_failure

## The fix

`WebTlsFront` now supplies its own key manager instead of reading the chain from
the keystore. `ServerKeyManager` (`WebTlsFront.kt:261`) answers
`chooseServerAlias`/`chooseEngineServerAlias` with the one alias, returns the
certificate the app holds from `getCertificateChain`, and returns the private key
read back from the keystore from `getPrivateKey`. The key still never leaves the
keystore; only the certificate, which is already public, is supplied directly.
`X509ExtendedKeyManager` is implemented rather than `X509KeyManager` so the
engine selection path is covered too.

`start()` also resolves the key before binding
(`WebTlsFront.kt:75`). A keystore that cannot serve a handshake now fails at start
where the service reports it, instead of leaving a listener that accepts and
hangs up. The certificate is passed in from `WebAccessService` at
`WebAccessService.kt:118`.

## Making it observable

An empty response with no reason recorded was itself the bug, so both a log and a
readable surface were added.

- **Log.** `WebTlsFront` records every failed close and calls the injected
  `log`, which the service points at `android.util.Log.i("WebAccess", ...)`
  (`WebAccessService.kt:121`).
- **Listener state.** New `WebDiagnostics.kt` holds a `ListenerDiagnostics`:
  `certificateLoaded`, `fingerprint`, `listenPort`, `backendPort`, `boundAddress`,
  `allowedAddresses`, `acceptedConnections`, `handshakeFailures`, `backendFailures`
  and `lastCloseReason`.
- **Route.** `GET /api/diagnostics` (`WebRelayServer.kt:139`, handler at `:211`)
  returns that state
  as JSON. It needs no session, because it is what the human reads when the
  session or TLS flow is the thing that is not working, and it carries no token,
  cookie or session data. It still passes the same `Host`, target, `Origin` and
  `Sec-Fetch-Site` checks as every other route, so a rebound name cannot read it.
- **Settings screen.** `WebAccessSection.kt:129` shows `diagnosticsSummary(...)`
  from the same state, so the reason is visible on the phone even when TLS is down
  and the browser cannot reach the route.

Close accounting distinguishes the two silent paths. A failure before any
application byte crosses on the client side is a failed TLS handshake
(`handshakeFailures`); no backend to dial is `backendFailures`; `stop()` sets
running false first so its own closes are never counted.

## Authentication

Unchanged. The `Host` allowlist, the same-site cookie, the CSRF token, the
JSON requirement, the `Origin`/`Sec-Fetch-Site` checks and the mutation rules are
all untouched, and `/api/diagnostics` is subject to the same `Host` and target
checks (a test asserts a foreign `Host` is still refused).

## Tests

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL   (75 tests, 0 failures, 0 errors; was 67)

Per class: BridgeUpstreamTest 5, SelfSignedCertificateTest 5, WebAddressesTest 7,
WebDiagnosticsTest 4, WebGuardTest 7, WebRelayServerTest 44, WebTlsFrontTest 3.

New:

- `WebTlsFrontTest.the front serves a certificate when the keystore holds a key
  with no chain`. Builds a keystore whose `KeyStoreSpi` returns the key and a null
  chain, exactly the AndroidKeyStore shape, and asserts a 200 over HTTPS. This is
  the regression for the empty response, and it fails against the old key manager
  (mutation below).
- `WebTlsFrontTest.the diagnostics route reports the backend port and the loaded
  certificate`. Fetches `/api/diagnostics` over HTTPS and asserts the backend
  port, the listen port, `certificateLoaded`, the fingerprint and the served
  address.
- `WebRelayServerTest.the diagnostics route reports the listener state without a
  session` and `... still refuses a foreign host`.
- `WebDiagnosticsTest` (4): the store records counters and the last reason and
  resets; the summary names the backend, the bind address, the unloaded
  certificate and a failed handshake with its reason.

The `Host` check accepting the served address is already covered by
`WebGuardTest.host must be present exactly once and match an allowed address` and
`WebRelayServerTest.a request to any candidate address is allowed`; both were
kept and are shown to fail under mutation.

Mutation checks, all restored byte-identical (`sha256sum -c` clean):

- Restored the old `KeyManagerFactory` line in `WebTlsFront.start`:
  `the front serves a certificate when the keystore holds a key with no chain`
  failed with `SSLHandshakeException: handshake_failure`.
- Made the relay record `backendPort = 0` and `allowedAddresses = emptyList()`:
  both diagnostics tests failed.
- Made `hostRefused` return false: six tests failed, including the `WebGuardTest`
  host test and `the diagnostics route still refuses a foreign host`.

## Gate

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL   (75 tests, 0 failures, 0 errors)

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL

## What only the human can confirm on the phone

- That the TLS handshake now completes on the device with the AndroidKeyStore key.
  The build machine proves the key-manager change with a software key and a
  no-chain keystore; the AndroidKeyStore key itself is the one part no JVM test
  can exercise. If it still fails, `/api/diagnostics` is not reachable and the
  logcat lines `WebAccess` in will name the reason.
- That `https://10.215.180.89:8788` now serves the page, and that
  `https://10.215.180.89:8788/api/diagnostics` returns
  `"certificateLoaded":true` with a non-zero `backendPort`.
- That the Settings screen shows the new Listener line, and that it turns red when
  a connection fails.
- That the browser warning still shows the fingerprint the phone displays, and
  that the addresses the phone lists are the ones the other computer can open.

## Deviations and things left as they were

- `/api/diagnostics` is deliberately session-free. It exposes only listener state,
  never a credential, and it is the route a human needs when the session flow is
  the thing that is broken. An attacker on the hotspot can poll it to keep the
  server from going idle, but the same attacker can already POST
  `/api/approve/request`, which is unauthenticated and rate-limited by design, so
  the incremental exposure is negligible.
- The diagnostics store is a process singleton, like `WebAccess` and `WebPeers`.
  Tests reset it in `@Before`; production has one server per process.
- `WebCertificateStore` was not changed. It still keeps the certificate in app
  storage and the key in the keystore; the front now pairs them itself rather than
  relying on the keystore to hold the chain. Installing the certificate into the
  keystore entry was the alternative and was not taken, because AndroidKeyStore's
  support for re-associating a certificate with an existing entry is not uniform
  and the explicit key manager works on every provider.
