# WLAN web access: stage 1 implementation (walking skeleton)

Branch `main`, based on `ec3632c0a6d3b0ac8629245a0c403f16d912e3e1`.
Stage 1 only, per design.md section 11. No auth, no cookie, no proxy, no prompts,
no streaming, no audio.

## What was built

- `app/src/main/java/com/maverock24/pimobile/web/WebAccessService.kt`: the
  foreground service that owns the server. `connectedDevice` type, a persistent
  notification that states the URL and carries a Stop action (`notification()`,
  `:133`), start and stop intents, and the idle watchdog (`startServer()`,
  `:76`) that calls `teardown()` after `WebAccess.IDLE_GRACE_MS`.
- `app/src/main/java/com/maverock24/pimobile/web/WebRelayServer.kt`: the Ktor CIO
  server that serves the one page. Binds loopback on an ephemeral port
  (`start()`, `:38`), tracks `inFlight` / `lastActivity` / `servedAnything` in an
  interceptor, and `isIdle()` (`:82`) is the grace-period test. `stop()` (`:89`)
  closes the listening socket.
- `app/src/main/java/com/maverock24/pimobile/web/WebTlsFront.kt`: the TLS
  listener on the Wi-Fi address. JSSE `SSLServerSocket` built from the keystore
  key (`start()`, `:52`), byte-pipes each accepted connection to the loopback CIO
  port. `stop()` (`:118`) closes the listening socket first.
- `app/src/main/java/com/maverock24/pimobile/web/WebCertificateStore.kt`: P-256
  EC key generated once into `AndroidKeyStore` under `pi-remote-web-access`
  (`generate()`, `:66`), the certificate persisted as DER in
  `filesDir/web-access/cert.der`, regenerated when the alias or the file is
  missing. The key is never written to storage.
- `app/src/main/java/com/maverock24/pimobile/web/SelfSignedCertificate.kt`: the
  no-Android half. `assembleSelfSigned()` (`:60`) builds the X.509 with
  BouncyCastle (`JcaX509v3CertificateBuilder`, `JcaContentSignerBuilder`,
  `JcaX509CertificateConverter`) and signs with `SHA256withECDSA` over P-256,
  provider-less so the Android keystore key can be used. `formatFingerprint()`
  (`:39`) and `fingerprintOf()` (`:43`).
- `app/src/main/java/com/maverock24/pimobile/web/WebAccess.kt`: the status the
  service writes and the UI reads (`StateFlow`), the fixed port `8788`
  (`:39`), and the 90 second grace period (`:47`).
- `app/src/main/java/com/maverock24/pimobile/web/WifiAddress.kt`: Wi-Fi IPv4
  lookup over `ConnectivityManager` and `LinkProperties`, picked per network so
  the Tailscale VPN does not hide the WLAN address.
- `app/src/main/java/com/maverock24/pimobile/ui/WebAccessSection.kt`: the Settings
  section (off/on state, URL, SHA-256 fingerprint, start and stop). Called from
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt:2099`.
- `app/src/main/assets/web/index.html`: the one page, no script.
- `app/src/main/AndroidManifest.xml:17` `CHANGE_WIFI_STATE`, `:18`
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `:80` the service with
  `foregroundServiceType="connectedDevice"`.
- `app/build.gradle.kts:97` `io.ktor:ktor-server-cio:3.0.2`; `:102`
  `bcpkix-jdk18on:1.81.1`; `:103` `bcprov-jdk18on:1.81.1`; `:105` JUnit for the
  tests only; `:69` a packaging exclude (see below).

## Contradiction in the design: Ktor CIO cannot do TLS

design.md section 3.1 and section 11 pair "Ktor server, CIO engine" with "over
TLS". They cannot both hold. `ktor-server-cio` refuses an HTTPS connector in its
own code, in every release through the current 3.6.0:

    ktor-server-cio 3.6.0, CIOApplicationEngine.kt:223
    if (connectorSpec.type == ConnectorType.HTTPS) {
        throw UnsupportedOperationException(
            "CIO Engine does not currently support HTTPS. Please " +
                "consider using a different engine if you require HTTPS")

Verified by unpacking `ktor-server-cio-jvm-3.6.0-sources.jar` from Maven Central
and reading the engine; the same throw is in 3.0.2, 3.0.3, 3.1.3, 3.2.3 and
3.3.3.

The task's dependency limit is Ktor CIO and BouncyCastle only, so switching to
`ktor-server-netty` (the engine that does support a TLS connector) would break
that limit and add several megabytes. Instead the TLS face is a small JSSE
`SSLServerSocket` (`WebTlsFront`) that forwards decrypted bytes to CIO on
loopback. Ktor still does the HTTP; the phone still terminates TLS with the
keystore key; the only WLAN listener is the TLS one. This is a deviation from the
design and should be accepted or replaced with Netty explicitly.

Consequence for stage 2: with the front in place, the backend CIO server sees
every request as coming from `127.0.0.1`. The section 5.3 approval is bound to
"the source address", so stage 2 must pass the real peer address across the
front (for example a header set by the front and stripped from any client
request), or the front must be replaced. Not built here because it is stage 2.

## Dependencies verified, not trusted from the design

- BouncyCastle artifact names: `bcpkix-jdk18on` is correct. Downloaded
  `bcpkix-jdk18on-1.81.jar` and confirmed it contains
  `org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder`,
  `org.bouncycastle.operator.jcajce.JcaContentSignerBuilder` and
  `org.bouncycastle.cert.jcajce.JcaX509CertificateConverter`. `bcprov-jdk18on`
  is the provider/ASN.1 jar (contains `BouncyCastleProvider`); `bcutil-jdk18on`
  comes in transitively. All three resolve to 1.81.1.
- `JcaContentSignerBuilder` defaults to `DefaultJcaJceHelper`, i.e. the platform
  `Signature`, not a named BouncyCastle provider (checked in the bcpkix 1.81
  sources, `build(PrivateKey)` calls `helper.createSignature(...)` then
  `sig.initSign(privateKey)`). That is what lets an Android keystore key sign.
- Curve and signature: P-256 with `SHA256withECDSA`, i.e.
  `ecdsa_secp256r1_sha256`. RFC 8446 section 9.1: "A TLS-compliant application
  MUST support digital signatures with ... ecdsa_secp256r1_sha256 ... MUST
  support key exchange with secp256r1 (NIST P-256)". Chrome and Firefox
  implement TLS 1.3, so both accept it.
- Ktor version: 3.0.2, not the design's 3.6.0. The app compiles with Kotlin
  2.0.21 (`build.gradle.kts` root). Ktor 3.0.2 was built with Kotlin 2.0.21
  (its `kotlin-stdlib` dependency) and coroutines 1.9.0, which is the app's
  version. Ktor 3.1 needs 2.1.20 and 3.6.0 needs 2.3.21, whose metadata the
  2.0.21 compiler rejects, so a matching Kotlin bump would be a wider change
  than stage 1 justifies. This is a deviation to record.
- The three BouncyCastle jars each ship
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF`, which the Android packager refuses
  to merge. `app/build.gradle.kts:69` excludes it. Without that the debug APK
  does not build, even though `compileDebugKotlin` does.

## What was verified, and how

Gate, exact command and tail:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    > Task :app:compileDebugKotlin
    BUILD SUCCESSFUL in 3s

The first resolution needed the network (Ktor, BouncyCastle, JUnit and their
transitive jars were not in the Gradle cache). Fetched from Maven Central:
`io.ktor:ktor-server-cio:3.0.2` and its tree (ktor-server-core, ktor-http-cio,
ktor-websockets, ktor-network, ktor-http, ktor-io, ktor-utils, ktor-events,
ktor-serialization, slf4j-api 2.0.16), `org.bouncycastle:bcpkix-jdk18on:1.81.1`,
`bcprov-jdk18on:1.81.1`, `bcutil-jdk18on:1.81.1`, and `junit:junit:4.13.2` plus
`hamcrest-core:1.3`. After that the gate and the tests run offline.

Unit tests, `:app:testDebugUnitTest --offline`: 3 tests, 0 failures, 0 errors
(`app/build/test-results/testDebugUnitTest/*.xml`). They cover the fingerprint
formatting and the certificate assembly on the JVM (`newP256KeyPair()` with the
platform provider): the assembled certificate parses, verifies under its own
public key, has `sigAlgName` SHA256withECDSA, a 256 bit EC field, and the IP
subject alternative name.

Mutation check on the fingerprint test. Removed the `and 0xFF` mask in
`formatFingerprint`; the test failed with

    expected:<00:01:7F:[80:AB:]FF> but was:<00:01:7F:[FFFFFF80:FFFFFFAB:FFFFFF]FF>

then the file was restored byte-identical (`diff -q` clean).

Full APK build, `:app:assembleDebug --offline`: BUILD SUCCESSFUL, so the app
dexes and packages with Ktor and BouncyCastle in it.

## What only the human can confirm on the phone

- That the foreground service starts from Settings on the target Android version
  with `connectedDevice` and `CHANGE_WIFI_STATE`, and posts the notification.
- That a browser on the WLAN reaches `https://<wifi-ip>:8788` and gets the page.
- Whether Conscrypt/Android's `SSLServerSocket` completes a handshake with the
  key held in `AndroidKeyStore` on that device. This is the one genuinely
  device-dependent part of the certificate work; it is standard JSSE usage but
  it has not been run.
- That the browser warning shows the same SHA-256 fingerprint the app displays.
- That the server stops by itself about 90 seconds after the browser goes away,
  that a page refresh inside that window does not stop it, and that the Stop
  action closes the socket.
- That binding the Wi-Fi address accepts inbound connections while the Tailscale
  VPN is up (design.md section 10 lists this as unverified).
- That the phone keeps the service alive with the screen off under its OEM
  battery manager. The Doze exemption prompt is not part of stage 1.

## Deviations and open items

- TLS is terminated by JSSE in front of CIO, not by CIO, because CIO refuses
  HTTPS. See above.
- Ktor is 3.0.2, matching the app's Kotlin, not 3.6.0.
- `bcprov-jdk18on` is declared explicitly although `bcpkix` pulls it in, so the
  ASN.1 types the assembly uses are unambiguously on the compile classpath.
- JUnit is a test-only dependency. It is the one added thing outside the runtime
  set of Ktor CIO and BouncyCastle; nothing from it reaches the APK.
- The full response hardening of section 3.3 (CSP, CORP, Host validation,
  Origin/Sec-Fetch-Site) is not built. It belongs with the session in stage 2;
  only `X-Content-Type-Options: nosniff` is set now.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and the Doze exemption prompt are not
  built (design section 9 items 4 and 7). They are not needed for the skeleton
  and the server is on demand.
- No QR for the URL. The task asked for the URL and the fingerprint; the QR is
  the design's convenience, deferred.
- The URL uses IPv4 only. IPv6 is out of scope per design section 3.2.
