# WLAN web access: the TLS handshake failed because the keystore key refused the handshake's digest

Branch `main`, based on `7c50a5a`. The human reports `openssl s_client` ending
with "unexpected eof while reading" and "no peer certificate available", the
browser showing `ERR_EMPTY_RESPONSE`, and the app logging `SSLHandshakeException`
"TLS handshake failed". The three earlier notes (`a33ddfa`, `63989ac`, `7c50a5a`)
all changed the certificate path. This note starts from those and does not repeat
them.

## There is no hand-written TLS engine loop to repair

The brief's hypothesis was that a hand-written `SSLEngine` state machine
mishandles `NEED_UNWRAP` and friends. That is not this code. `WebTlsFront` has
been a blocking `SSLServerSocket` since stage 1 (`727226e`), which is the
architecture the brief recommends as the fix. Rechecked at HEAD:

    grep -rn "NEED_UNWRAP\|NEED_WRAP\|BUFFER_OVERFLOW\|BUFFER_UNDERFLOW" app/src/main/java
    (no matches)

The only `SSLEngine` mentions are the two `chooseEngineServerAlias` parameters on
`ServerKeyManager`, which is the key manager, not an engine loop. So the fix is
neither "remove the engine loop" nor "repair the engine loop": the loop is already
the blocking server socket, and the fault was in the key the loop is handed.

## What is actually wrong

An Android keystore key is only usable with the digests it was created with.
`WebCertificateStore.generate` created the P-256 key with `.setDigests(DIGEST_SHA256)`,
so SHA-256 is the only authorized digest.

The Android TLS stack does not compute the signature hash itself. Conscrypt hands
the digest it computed to the key as a raw, no-digest ECDSA signature:

- `common/src/main/java/org/conscrypt/CryptoUpcalls.java`:
  `ecSignDigestWithPrivateKey` signs with `Signature.getInstance("NONEwithECDSA")`.
- `common/src/jni/main/cpp/conscrypt/native_crypto.cc`: `EcdsaMethodSign` calls up
  into that with `ecSignDigestWithPrivateKey`.
- AOSP's `AndroidKeyStoreBCWorkaroundProvider` maps `NONEwithECDSA` to
  `AndroidKeyStoreECDSASignatureSpi$NONE`, i.e. `KM_DIGEST_NONE`.

KeyMint then rejects the operation, because the key's characteristics hold only
SHA-256 and the operation asks for NONE:

- `common/src/tag.rs`, `check_key_authorizations`:
  `if check.digest { if !contains_tag_value!(chars, Digest, *digest) { return Err(km_err!(IncompatibleDigest, ...)) } }`
- `check_begin_ec_params` returns `digest: true` for an EC signing key.

So `Signature.getInstance("NONEwithECDSA").initSign(key)` throws
`InvalidKeyException` at `begin()`. Conscrypt's upcall catches that, logs, and
returns null; `EcdsaMethodSign` then fails and the handshake aborts. In TLS 1.3
the Certificate message is inside the encrypted flight, so nothing reaches the
client, which is exactly "no peer certificate available" and "unexpected eof". The
Java `SSLHandshakeException` that reaches the app carries no cause, because the
upcall swallowed the keystore exception, which is why the earlier `failureChain`
work could not name it.

## The fix

One line plus a guard and a migration:

1. **`WebCertificateStore.kt:120`** now creates the key with
   `.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)`. SHA-256
   stays because the app signs the certificate and the key-match challenge with
   it; NONE is added because that is what the handshake asks for.
2. **`SelfSignedCertificate.kt:131`** `keySignsRawDigest` signs a fixed digest
   with `NONEwithECDSA`, trying every provider that offers it, because the TLS
   stack does the same (the first provider is the TLS engine, which cannot use a
   non-exportable keystore key; a later provider can). It is the property that
   decides whether a key can serve TLS.
3. **`SelfSignedCertificate.kt:169`** the disposition now rebuilds the pair when
   the key cannot serve TLS. This is the migration: a device that already holds a
   SHA-256-only key matches its certificate and its addresses, so without this it
   would be reused and the bug would survive the update. Now it is discarded and
   `generate` makes the fixed key.
4. **`WebTlsFront.kt:96`** the front also refuses at start a key that cannot sign
   a raw digest, so the last line of defence names the cause where the service can
   show it instead of binding a listener that accepts every connection and hangs
   up. Like the earlier `certificateMatchesKey` check this is a start-time failure
   the Settings screen reports.

The authentication is untouched: the approval gate, session cookie, CSRF token,
exact Host check, Origin and Sec-Fetch-Site checks are all as they were. Nothing
was relaxed. The front is still the blocking `SSLServerSocket` with
`ServerKeyManager`; only the key's digest set changed.

## Why not switch the key off the keystore

Hardware backing and the design in section 12 both want the key in the keystore,
and the keystore can serve TLS once it is allowed the digest the handshake uses.
Removing `setDigests` altogether was the other option (no restriction would admit
NONE); naming both digests is the smaller grant and documents why.

## Tests

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL   (88 tests, 0 failures, 0 errors; was 84)

Per class: BridgeUpstreamTest 5, SelfSignedCertificateTest 15, WebAddressesTest 7,
WebDiagnosticsTest 4, WebGuardTest 7, WebRelayServerTest 44, WebTlsFrontTest 6.

New:

- `SelfSignedCertificateTest.a key that can make the raw-digest signature TLS asks
  for is accepted`: a P-256 key passes `keySignsRawDigest`.
- `SelfSignedCertificateTest.a key that cannot make the raw-digest signature TLS
  asks for is refused`: an RSA key fails it, standing in for the keystore key whose
  digests do not include NONE.
- `SelfSignedCertificateTest.a certificate whose key cannot serve TLS is rebuilt`:
  the disposition returns REGENERATE through the injected `keyCanServeTls`.
- `WebTlsFrontTest.the front refuses to start when the key cannot sign a raw
  digest`: `start` throws and binds nothing.

Mutation checks, all restored byte-identical (`sha256sum -c` clean):

- `keySignsRawDigest` forced to true: the refusal test failed.
- `keySignsRawDigest` forced to false: the acceptance test and the two disposition
  REUSE/REISSUE tests failed.
- The `keyCanServeTls` line removed from the disposition: the rebuild test failed.
- The `keyCanServeTls` check removed from `WebTlsFront.start`: the front test
  failed.

The two injection seams (`keyCanServeTls` on `certificateDisposition` and on
`WebTlsFront`) exist because the only key that fails the check is an AndroidKeyStore
key restricted to the wrong digest, which the build machine cannot create. They
match the way the service already injects its log, assets and upstream.

## What only the human can confirm on the phone

- That a browser now reaches the certificate warning and then the page, and that
  `openssl s_client -connect 10.215.180.89:8788` now shows a peer certificate.
- That the fingerprint the browser shows is the one the Settings screen shows. It
  will have changed, because the key is regenerated once with the corrected
  digests; the warning is accepted again.
- That if it still fails, the Settings screen or logcat under `WebAccess` names the
  reason. A key that cannot serve TLS now fails at start with "The web access key
  cannot sign a raw digest, which TLS requires", not per connection.

## Uncertain

- The digest enforcement is read from the KeyMint and Conscrypt sources, not
  observed on the device. It is the only failure consistent with every symptom
  (accepted TCP, no certificate, `SSLHandshakeException`, works on the JVM where
  software keys impose no digest restriction).
- Whether the handset's key backing supports `DIGEST_NONE` at generation. If it
  does not, `generate` throws and the service reports it at start, which is a
  visible failure rather than a silent one. The P-256 key here is not
  StrongBox-bound, so the common case should be fine.
