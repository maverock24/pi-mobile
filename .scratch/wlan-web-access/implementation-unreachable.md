# WLAN web access: why the page was not reachable

Branch `main`, based on `d12cd88`. The human reports "none of the listed ip
adresses open the ui in the browser", and they run code recent enough to list
addresses (the hotspot fix in `001b967`). This note records what I checked, the
one defect I could prove, and what only the phone can settle.

## What the server binds

Two listeners, on purpose, and only the second is on the network.

- **Ktor CIO** binds `host = "127.0.0.1"` on an ephemeral port (`WebRelayServer.start`,
  `WebRelayServer.kt:91` and `:94`; the bound port comes back from
  `resolvedConnectors` at `:159`). Ktor's CIO engine refuses an HTTPS connector of
  its own, so this half is plain HTTP behind the TLS front; binding loopback keeps
  it off the WLAN entirely.
- **The TLS front** is the WLAN listener. It calls
  `createServerSocket(port, BACKLOG)` with no address, i.e. the wildcard, on
  `WebAccess.PORT` = 8788 (`WebTlsFront.start`, `WebTlsFront.kt:57` and `:68`).

Proof it is the wildcard and not one interface, from the compiled class:

    javap -c .../WebTlsFront.class | grep createServerSocket
    invokevirtual javax/net/ssl/SSLServerSocketFactory.createServerSocket:(II)Ljava/net/ServerSocket;

The two-argument form has no `InetAddress`, so it binds `0.0.0.0`. A listener bound
to one interface would use the three-argument form. So hypothesis 1 (listener
bound to one of the addresses it lists) does not hold: the wildcard accepts on
every interface the phone holds, which is why the whole displayed list is meant to
answer.

## What the app displays

`WebAddress.url(port)` is `"https://$address:$port"` (`WebAddresses.kt:31`), and it
is the only string shown, in all three places:

- the Settings subtitle, `"On · ${it.url(WebAccess.PORT)}"` (`WebAccessSection.kt:54`),
- each address in the list, `candidate.url(WebAccess.PORT)` (`WebAccessSection.kt:86`),
- the service notification, `"Serving ${it.url(WebAccess.PORT)}"` (`WebAccessService.kt:165`).

So the human sees `https://192.168.43.1:8788`, with the scheme and the port.
Hypothesis 2 (a bare address that lands on port 443) does not hold either.

## Scheme, certificate, warning

The scheme is right: the one listener is TLS, and the shown string is `https://`.
The certificate is self-signed and the settings text names the warning:
`WebAccessSection.kt:108` ("The browser will warn about a self-signed
certificate..."). The certificate names the addresses as IP subject alternative
names at build time (`SelfSignedCertificate.kt:83`).

The defect: the certificate was minted once and reused forever. `load` kept any
stored certificate that merely passed `checkValidity` (ten years), so an address
that appeared after the first run (the hotspot address, after the Wi-Fi address the
early stages used) was never named. A browser matches the URL against those names,
so the served certificate stopped covering what the app advertises.

## The service and the list

The list is published only after the listener binds, on the same code path:
`startServer` assigns `server`, then `tls.start()`, and only then
`WebAccess.update(WebAccessStatus.On(addresses, ...))` (`WebAccessService.kt:110-118`).
`refreshAddresses` re-reads the interfaces every five seconds and updates the
allowed set and the shown list together (`WebAccessService.kt:145`). So a shown
address implies a bound listener. Hypothesis 4 does not hold.

## The fix

`WebCertificateStore.load` now reuses a stored certificate only when it names every
current address, and otherwise re-signs (`WebCertificateStore.kt:61` and `:82`).
Re-issuing keeps the keystore key and takes the public key from the stored
certificate, because an Android keystore entry holds a key and not a certificate.
If the key does not match that public key, the pair is rebuilt, so a mismatched
certificate is never served. The fingerprint therefore changes when the phone's
addresses change, which the Settings screen and the notification read fresh; that
is the price of a certificate that names the addresses the app tells the human to
open. The self-signed warning is unchanged.

Two pure helpers were added so the rule is tested without a phone:
`subjectAltIpsOf` and `addressesNotNamed` (`SelfSignedCertificate.kt:54` and `:66`).
`WebTlsFront.boundAddress()` (`WebTlsFront.kt:81`) exposes the bound address so a
test can prove the wildcard rather than describe it.

## Tests

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    67 tests, 0 failures, 0 errors (was 63)
    BridgeUpstreamTest 5, SelfSignedCertificateTest 5, WebAddressesTest 7,
    WebGuardTest 7, WebRelayServerTest 42, WebTlsFrontTest 1

New:

- `WebTlsFrontTest.the front binds every interface and answers on a non-loopback
  address`: starts the front and connects over HTTPS to an interface address, not
  loopback, and asserts `boundAddress().isAnyLocalAddress`. It fails if the front
  binds one address (proven by mutation below).
- `WebAddressesTest.the displayed url carries the scheme and the port the server
  binds`: asserts the shown string is `https://<ip>:8788` using the real
  `WebAccess.PORT`.
- `SelfSignedCertificateTest` (2): a certificate names every address it was built
  for, and an address it does not name is reported missing.

Mutation checks, both restored byte-identical (`sha256sum` matched before and
after):

- Bound `127.0.0.1` in `WebTlsFront.start` instead of the wildcard:
  `WebTlsFrontTest` failed with "the listening socket is bound to the wildcard
  address, not 172.18.0.1".
- Changed the SAN filter in `subjectAltIpsOf` from `iPAddress` to `dNSName`: both
  new `SelfSignedCertificateTest` cases failed.

## What only the human can confirm on the phone

- That the phone's interface list actually contains the hotspot access-point
  address, so the Settings screen shows the address the other computer must type.
  This is not testable off-device and is the first thing to check.
- That the browser warning now names the address the human typed rather than
  warning about a name as well as the self-signed certificate.
- That the TLS handshake completes on the device with the Android keystore key
  (the JVM test uses a software key; the Android keystore path is the one part of
  the front the build machine cannot exercise).
- That the other computer is on the phone's own network (the hotspot), since a
  computer on a different network cannot reach any of the address the phone holds.

## Uncertain

- I could not reproduce the reported failure in the code. The bind, the displayed
  string and the start order are all correct, so the remaining on-device causes are
  the three in the list above. If the interface list omits the access-point
  address, that is a separate fix (reading the tethered address from
  `ConnectivityManager`/`WifiManager` in addition to `NetworkInterface`), and I did
  not make it without a device to confirm the enumeration.
- The re-issue on an address change is a deliberate reversal of the earlier note
  ("a stable fingerprint is worth more than a name that matches"), because the
  served name is what the browser checks against the URL the app displays.
