# WLAN web access: hotspot transport fix

Branch `main`, based on `d60f471` (stage 4). This is not a design stage; it fixes
the gate that stopped the server starting on the transport section 15 chose.

## The bug, and where it was

`app/src/main/java/com/maverock24/pimobile/web/WifiAddress.kt:27` was

    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

and `WebAccessService.kt:86-87` refused when it found nothing:

    val address = wifiIpv4(this@WebAccessService)
        ?: error("The phone is not on a Wi-Fi network")

`wifiIpv4` asked `ConnectivityManager` for a network with the Wi-Fi transport and
took its first IPv4. On the phone's own hotspot the phone is the access point: its
Wi-Fi *client* interface can be down, and the access-point interface is usually not
a `Network` `ConnectivityManager` reports at all. So the lookup returned null and
the server refused with "The phone is not on a Wi-Fi network" even though the
hotspot was up and the browser could have reached it. The check was written for the
Wi-Fi-client reading the design section 1 first assumed, and sections 14 and 15
replaced that reading with the hotspot.

## What changed

**1. No Wi-Fi client requirement.** `WifiAddress.kt` is deleted (grep for
`wifiIpv4` before deletion found only its definition and the one call site). Its
replacement, `WebAddresses.kt`, enumerates `NetworkInterface.getNetworkInterfaces()`
and keeps every non-loopback, non-link-local IPv4 address on an interface that is
up. `ConnectivityManager` is now used only to *label* an interface with its
transport (`transportLabels`, `WebAddresses.kt:118`); it is never a gate. The
service fails loudly only when the list is empty
(`WebAccessService.kt:90`), which is genuinely no usable address.

**2. Every candidate is shown, none is guessed.** `WebAddresses.kt:23` defines
`WebAddress(address, interfaceName, transport)`. `webAddresses` (`:68`) returns all
of them, unlabelled interfaces (usually the hotspot AP) first, VPN last. The
interface name is carried through so the Settings screen can label each address;
the tethering interface name varies by manufacturer (ap0, wlan1, swlan0, softap0),
so nothing is matched by name. `ui/WebAccessSection.kt:76` lists every candidate
with its label and says that if one does not answer the human should try the next,
which is section 14's rule that a wrong address is worse than none.

**3. Binding.** `WebTlsFront` no longer takes a `bindAddress` and binds the wildcard
address (`WebTlsFront.kt:66`, `createServerSocket(port, BACKLOG)` with no address).
Binding one guessed address was the same bug in a different place: the hotspot
address has no fixed interface name and can appear after the server starts. This is
a deviation from design 3.2, which bound the Wi-Fi address specifically to avoid
listening on the Tailscale and cellular interfaces; sections 14 and 15 supersede it
for this transport. The `Host` allowlist, the exact-address set, is what keeps the
DNS-rebinding control, and the TLS handshake and session approval are unchanged.

**4. The Host/Origin set widened to match.** `WebGuard.hostRefused` and
`originRefused` now take a `Set<String>` of allowed authorities and origins instead
of one string. `WebRelayServer` takes `allowedAuthorities`/`allowedOrigins` and
exposes `updateAuthorities` (`WebRelayServer.kt:60`). The set is always literal
addresses, never a name, so a rebound name still fails closed. The certificate
names every candidate as a subject alternative name (`SelfSignedCertificate.kt:81`),
for the same no-guessing reason.

**5. The address list updates while the server runs.** The watchdog loop re-reads
the interfaces every five seconds and, when the set changes, calls
`relay.updateAuthorities`, rewrites the status and the notification
(`WebAccessService.kt:145`, `refreshAddresses`). With no address left the allowed
set is empty, so every request is refused rather than answered for a stale address.

**6. Doze exemption (design section 9 item 7).**
`AndroidManifest.xml:19` declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
`ui/WebAccessSection.kt:194` opens the system exemption dialog when the human
starts the server, but only if the app is not already exempt, and wraps it in
`runCatching`. The server is started either way, so a refusal or a device without
that Settings screen costs the asleep case and never the ability to serve.

## Tests

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline`
55 tests, 0 failures, 0 errors (stage 4 had 46). Per class: BridgeUpstreamTest 4,
SelfSignedCertificateTest 3, WebAddressesTest 6, WebGuardTest 7,
WebRelayServerTest 35.

New in `WebAddressesTest` (6). The one that matters is
`a hotspot interface with no Wi-Fi client transport is still a candidate`: it feeds
only an unlabelled `ap0` and a `mobile` interface and asserts both survive. That is
the removed gate, asserted rather than described. The others cover the hotspot-first
order, the interface label, dropping loopback/link-local/down interfaces, de-duping
one address held twice, and the URL.

New in `WebRelayServerTest` (3): `a request to any candidate address is allowed`,
`an address that is not a candidate is refused`, and
`the allowed addresses follow the interfaces`, which narrows the set, confirms the
dropped address is refused, widens it again, then empties it and confirms a stale
address is refused. `WebGuardTest` drives the set form of the two checks.
`SelfSignedCertificateTest` now asserts two candidate SANs are present.

Mutation checks, all restored byte-identical (`diff -q` clean, SHA-256 of
`WebAddresses.kt` unchanged):

- Added the old Wi-Fi gate back to `webAddresses` (`.filter { it.transport ==
  "Wi-Fi" }`): `a hotspot interface with no Wi-Fi client transport is still a
  candidate`, the order test and the label test all failed.
- Made `updateAuthorities` a no-op: `the allowed addresses follow the interfaces`
  failed.
- Made `assembleSelfSigned` name only the first address: `assembled certificate is a
  self-signed p256 certificate` failed.
- Made `hostRefused` compare against the first allowed authority only:
  `a request to any candidate address is allowed`, `the allowed addresses follow the
  interfaces` and the `WebGuardTest` host test failed.

## Gate

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL in 2s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL in 4s   (55 tests, 0 failures, 0 errors)

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL in 6s

## What only the human can confirm on the phone

- That the server now starts on the hotspot with the Wi-Fi client down, and that
  the Settings screen lists the hotspot address with its interface name.
- **That the hotspot address actually answers from another computer.** The bind is
  wildcard, so the address is reachable in the kernel's sense, but nothing here has
  run on a device.
- That the Doze dialog appears, that declining it still leaves the server serving
  with the screen off for a while, and that granting it keeps the session alive
  through Doze.
- That the address list updates when the hotspot or Wi-Fi is toggled while the
  server runs, and that the notification shows the new address.
- That the TLS handshake still works on a wildcard bind with the Android keystore
  key.
- That the Host allowlist does not break a browser: the browser's `Host` is the
  address the human typed, which must be one of the listed candidates.

## Deviations and things left as they were

- **The bind is the wildcard address, not the Wi-Fi address as design 3.2 says.**
  Sections 14 and 15 supersede 3.2 for the hotspot transport, and the address to
  bind cannot be identified reliably. The server now also listens on the Tailscale
  and cellular interfaces. The auth controls are untouched, so this widens where the
  listener is reachable but not who can use it. If the human wants hotspot-only, the
  next step is an app-owned `LocalOnlyHotspot`, which section 15 rejected.
- **The source address a session is bound to is still the peer TCP address**
  (`WebSession.sourceAddress`), unchanged. On the hotspot that is a client of the
  AP, which is what the approval screen shows.
- **The five second refresh is a poll, not a `NetworkCallback`.** The access-point
  interface is frequently not a `Network` a callback would fire for, so polling the
  interface list is the reliable route; the cost is up to five seconds of staleness.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is Play-policy restricted.** It is what
  section 9 item 7 chose. If the app is distributed on Play, this permission may
  need a declaration or removal; that is a distribution decision, not a code one.
- The certificate is generated once and kept, so an address that first appears
  after the certificate exists is not in its subject alternative names. The browser
  warns about the self-signed certificate regardless, so this only affects a second
  warning line.
