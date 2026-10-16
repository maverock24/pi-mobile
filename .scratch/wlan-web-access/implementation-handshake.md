# WLAN web access: why the handshake still failed

Branch `main`, based on `63989ac`. The human reports the app's own
`SSLHandshakeException`, "TLS handshake failed", with the outward listener up
and the browser showing nothing. The two earlier attempts changed the
certificate re-issue rule (`a33ddfa`) and the key manager (`63989ac`); this note
starts from those and does not repeat them.

## What is already correct, checked first

- **Key spec.** `WebCertificateStore.generate` (`WebCertificateStore.kt:107`)
  builds the P-256 key with both the curve and an explicit digest:
  `.setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))` and
  `.setDigests(KeyProperties.DIGEST_SHA256)`. The "digest missing so every
  signature fails" trap does not apply here; it has been that way since stage 1
  (`727226e`). Confirmed by reading, not assumed.
- **Key manager.** `63989ac` already replaced JSSE's chain-reading manager with
  `ServerKeyManager` (`WebTlsFront.kt:261`), which answers the alias, the
  certificate the app holds, and the key read back from the keystore. That part
  is not the cause either.

## The cause that is left in the code

`WebCertificateStore.load` decided to reuse the stored certificate from three
things only: the alias existed, the certificate passed `checkValidity`, and it
named every current address. It never asked whether the keystore key it was
about to hand to TLS was the key the certificate was issued for. The old
decision was one line:

    if (stored != null && isUsable(stored) && addressesNotNamed(stored, subjectAltIps).isEmpty()) {
        return WebCertificate(keyStore, ALIAS, stored, fingerprintOf(stored))

`WebTlsFront` then signs the handshake with `keyStore.getKey(alias)` and
presents `stored`. When those two have come apart the listener starts fine, the
certificate is offered, and the signature does not verify against it, so the
handshake ends with `SSLHandshakeException` and the browser gets nothing. That
is the reported shape. The two ways they come apart are exactly the ones the
brief names: a keystore reset, or a key remade under the alias while the stored
certificate stayed behind; and a device whose key cannot sign at all, which
`checkValidity` cannot see because the failure is in the signature, not the
certificate.

The old `reissue` path did catch a mismatch, but only as a side effect of
signing a fresh certificate and calling `certificate.verify(publicKey)`, and
only on the address-change path. The reuse path, which is the one taken on every
ordinary start, checked nothing.

## The fix

1. **A key-match check that does not need a device.**
   `certificateMatchesKey` (`SelfSignedCertificate.kt:98`) signs a fixed
   challenge with the private key and verifies it under the certificate's public
   key, which is the property the handshake needs. It is provider-less on
   purpose, like the assembly, so an Android keystore key can sign. A key made
   with a digest it cannot use fails this check rather than the handshake.
2. **The decision is one plain function.**
   `certificateDisposition` (`SelfSignedCertificate.kt:119`) returns REUSE only
   when the certificate is present, valid, belongs to the key, and names every
   address. A moved name is REISSUE over the same key; a missing key, an expired
   certificate, or a certificate that does not belong to the key is REGENERATE.
   `load` (`WebCertificateStore.kt:56`) now dispatches on it, so a pair that has
   come apart is rebuilt instead of served. The match check is the new gate; the
   normal cases behave as before.
3. **The front refuses a pair it is ever handed.**
   `WebTlsFront.start` (`WebTlsFront.kt:82`) now checks the pair before binding,
   so the last line of defence is also a start-time failure the service reports,
   not a listener that accepts and hangs up.
4. **The recorded reason carries the cause.**
   `failureChain` (`WebTlsFront.kt:326`) walks the cause chain, and both close
   paths record it (`WebTlsFront.kt:143` and `:204`). The wrapper alone
   ("SSLHandshakeException") names the symptom; the digest or certificate cause
   underneath is a cause, and it now reaches logcat, `lastCloseReason`, the
   Settings Listener line and `/api/diagnostics`.

## What the fix changes for normal reality

- Reinstall: keystore entry and `filesDir` both go, so disposition is
  REGENERATE. Unchanged.
- Address change: REISSUE over the same key. Unchanged.
- Key remade while the certificate lives on, keystore reset that leaves the
  alias, or a key that cannot sign: previously REUSE and a dead handshake, now
  REGENERATE, a fresh pair, and a working listener. This is the fix.
- Hardware-backed versus software keystore: no code path depends on which one is
  in use, and the match check runs on whichever provider answers.

## Tests

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL   (84 tests, 0 failures, 0 errors; was 75)

Per class: BridgeUpstreamTest 5, SelfSignedCertificateTest 12, WebAddressesTest
7, WebDiagnosticsTest 4, WebGuardTest 7, WebRelayServerTest 44, WebTlsFrontTest
5.

New on the plain JVM, no device:

- `SelfSignedCertificateTest` (7): the match check accepts the certificate's own
  key and rejects a different one; the disposition reuses a certificate that
  belongs to the key and names every address, re-signs one whose names have
  moved, and rebuilds one whose key is missing, whose certificate is missing,
  that has expired, or that does not belong to the keystore key.
- `WebTlsFrontTest.a handshake failure chain names the cause and not only the
  symptom`: asserts the recorded chain contains the wrapper, the cause class and
  the cause's message.
- `WebTlsFrontTest.the front refuses to start when the certificate and the key do
  not match`: a keystore holding a different key under the alias makes `start()`
  throw and binds nothing.

Mutation checks, all restored byte-identical (`sha256sum -c` clean):

- Made `certificateMatchesKey` ignore `verify`'s result: `the key match check
  rejects a key...` and `a certificate that does not belong to the keystore key
  is rebuilt` failed.
- Made the disposition return REGENERATE where it should REISSUE: `a certificate
  that does not name every address is re-signed over the same key` failed.
- Made `failureChain` return only the first line: `a handshake failure chain
  names the cause...` failed.
- Disabled the front's start check: `the front refuses to start when the
  certificate and the key do not match` failed.

Note: `certificateMatchesKey` is not tested against a real Android keystore,
because there is none on the build machine. The pattern it uses (provider-less
`Signature` over the keystore key) is the same one the app already relies on to
sign the certificate itself, so a device that could ever serve a certificate at
all can run this check.

## What only the human can confirm on the phone

- That the handshake now completes with the AndroidKeyStore key and the browser
  reaches the certificate warning and then the page.
- That the fingerprint the browser shows is the one the Settings screen shows.
- Which of the disagreements was the one on that device: logcat under the
  `WebAccess` tag, or the red Listener line, now names the cause chain when a
  connection fails. If it still fails there, that line is the first thing to
  read.

## Deviations and things left as they were

- `setDigests` and `ServerKeyManager` were left exactly as they are; they are
  correct and are not the cause.
- `/api/diagnostics`, the Settings Listener line, the `Host` check and the rest
  of the authentication are untouched.
- The store still writes only the certificate and never the key, per design
  section 12.
