# WLAN web access: asset content types

Branch `main`, based on `84c4c48` (the keystore signing fix). The web server
already completed TLS, served its certificate and answered `GET https://<ip>:8788/`
with `200 OK` and the page body, but the browser downloaded the page instead of
rendering it.

## The bug

The response for `/` carried `Content-Type: application/octet-stream`, so the
browser had no reason to treat the body as a document.

The static handler is the `ASSETS` loop at
`app/src/main/java/com/maverock24/pimobile/web/WebRelayServer.kt:123`. Each route
was served with `contentTypeFor(path)`, and `contentTypeFor` decided the type from
the **request path**. The route that names the shell is `"/"`
(`ASSETS`, `:995`; the entry is `:996`: `"/" to "web/index.html"`), and `"/"` has
no extension, so it fell through to `ContentType.Application.OctetStream`. The file
it points at, `web/index.html`, does have one; the path did not.

The same map keys carry the extension for the other assets
(`/style.css`, `/app.js`, `/lib/*.js`, `/screens/*.js`), so those happened to get
the right type from the path. The fix makes the decision uniform and correct for
the root, which is the only key without an extension.

## The fix

- `WebRelayServer.kt:124`: the loop now calls `contentTypeFor(asset)`, the asset
  name (`web/index.html`) rather than the request path (`/`).
- `WebRelayServer.kt:746`: `contentTypeFor` is now a `when` on
  `extensionOf(assetName)`, the same lowercased-extension helper the audio route
  already used (`extensionOf`, `:788`). Text types get
  `.withCharset(Charsets.UTF_8)`, which the Ktor `ContentType.Text.*` constants do
  not carry by themselves.

The map, and why each entry:

| extension | type |
| --- | --- |
| `html`, `htm` | `text/html; charset=utf-8` |
| `css` | `text/css; charset=utf-8` |
| `js`, `mjs` | `text/javascript; charset=utf-8` |
| `json` | `application/json; charset=utf-8` |
| `svg` | `image/svg+xml` |
| `png`, `jpg`/`jpeg`, `gif` | `image/png`, `image/jpeg`, `image/gif` |
| `webp` | `image/webp` |
| `ico` | `image/x-icon` |
| `woff`, `woff2` | `font/woff`, `font/woff2` |

None of the image or font entries is used today: `app/src/main/assets/web/`
holds only `.html`, `.css` and `.js` (checked with `find`). They are in the map
because the map is the one place the type is decided and the next asset added
should not have to rediscover this bug.

**Unknown extension.** The `else` branch stays `application/octet-stream`.
`ASSETS` is a literal list of files shipped in the APK (`:995`), so an extension
not in the map is a build-time mistake, not untrusted input, and
`X-Content-Type-Options: nosniff` (set on every response, `harden`, `:692`, header at
`:693`) stops the browser guessing a kind the bytes are not. OctetStream is the
honest answer for bytes whose kind is unknown; it is not a default for kinds that
are known.

`contentTypeFor` is only used by the asset loop. The JSON routes use
`respondJson` (`ContentType.Application.Json`, `:725`), and the audio route uses
`audioContentType` (`:773`), so neither is touched. Confirmed by grep:
`contentTypeFor` appears at `:124` (call) and `:746` (definition) only.

## The test

`app/src/test/java/com/maverock24/pimobile/web/WebRelayServerTest.kt:753`,
`the shell and its modules are served with the type their extension implies`. It
fetches `/` and asserts `text/html` plus a charset, fetches `/app.js` and asserts
`text/javascript` plus a charset, and fetches `/style.css` and asserts `text/css`.
The test's fake asset loader returns the same string for every name
(`WebRelayServerTest.kt:70`), so the type can only come from the `ASSETS` key, which
is what the test is about.

Mutation check: reverting the call to `contentTypeFor(path)` (the original bug)
fails it with
`AssertionError: the shell must be HTML or the browser downloads it: application/octet-stream`,
which is the exact symptom the human reported. The file was restored and compared
byte for byte with `diff -q` before the next run.

## The other browser-facing difference

The JSON routes, the SSE route and the session route all set
`Cache-Control: no-store` (`:726`, `:558`). An API client ignores that header; a
browser does not. The asset loop set no cache directive at all, so a browser could
serve a cached shell after an app update, and design.md section 4.3 says offline
caching is not wanted because the phone is the source of everything. The loop now
sets `Cache-Control: no-store` on each asset (`:123`), matching the routes beside
it, and the hardening test asserts it (`WebRelayServerTest.kt:748`).

## What was checked and found fine

- The cookie is `HttpOnly; SameSite=Strict; Path=/; Secure` (`WebSessions.kt:83`),
  correct for the HTTPS origin.
- The CSP `script-src 'self'` is satisfied by the modules once their type is
  JavaScript; the page has no inline script, no inline style, no `eval`, so no
  `unsafe-inline` or `unsafe-eval` is needed.
- The security headers are set on the shell as on every response (`harden`,
  `:692`). `X-Content-Type-Options: nosniff` is what makes the wrong type fail
  loudly rather than being guessed, so it stays.

## Not changed

- A browser requests `/favicon.ico`, which is not in `ASSETS` and 404s. That is a
  console 404, not a broken page, and the route list is a deliberate literal
  allowlist, so no favicon route was added.
- `HEAD` is not registered and the `AutoHeadResponse` plugin is not installed, so
  a HEAD request to an asset is a 404. No browser navigation needs it.
