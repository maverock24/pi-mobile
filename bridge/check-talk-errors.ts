/**
 * Stored render-error check.
 *
 * A failed render leaves a renderer exception in the talk record, and the bridge
 * serves that string to the phone and to render-talk.sh. A raw Python repr
 * carries a dotted module path, a `message='...'` wrapper and the request URL,
 * and a URL's query string can carry a token; a phone row is one line in any
 * case. This checks that the serving helper returns one readable line: it keeps
 * the reason, drops the URL, strips the module path and the wrapper, and never
 * returns a newline. The real-shape case is the exact edge-tts failure the human
 * saw reported as "message=invalid response header".
 *
 * Run it directly, node strips the types:
 *   node bridge/check-talk-errors.ts
 */

import { readableTalkError } from "./lib/talks.ts";

let failures = 0;

function check(name: string, ok: boolean, detail: string): void {
	if (ok) {
		console.log(`ok   ${name}`);
	} else {
		failures += 1;
		console.log(`FAIL ${name}: ${detail}`);
	}
}

// The stored error from the talk the human asked about, verbatim from
// ~/.pi/agent/talks/cc5793ff62ef72a4.json.
const edgeTts =
	"aiohttp.client_exceptions.WSServerHandshakeError: 403, message='Invalid response status', " +
	"url='wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId=69be9bca67c9430686a0297c75b0afca'";

const cleaned = readableTalkError(edgeTts);
check(
	"the module path and the message wrapper are dropped",
	cleaned === "WSServerHandshakeError: 403, Invalid response status",
	String(cleaned),
);
check("no url or token survives", !String(cleaned).includes("http") && !String(cleaned).includes("TrustedClientToken"), String(cleaned));
check("the result is one line", !String(cleaned).includes("\n"), String(cleaned));

check(
	"a traceback is reduced to its last line",
	readableTalkError("Traceback (most recent call last):\n  File \"x.py\", line 1\nRuntimeError: boom") === "RuntimeError: boom",
	"not the last line",
);
check(
	"a plain renderer line is unchanged",
	readableTalkError("render-kokoro: the script is empty") === "render-kokoro: the script is empty",
	"changed a line that needed no cleaning",
);
check("an absent error stays absent", readableTalkError(null) === null, "null became a string");
check("an empty error becomes absent", readableTalkError("   \n  ") === null, "blank became a string");
check("a long line is capped", String(readableTalkError("x".repeat(1000))).length === 300, "not capped to 300");

if (failures > 0) {
	console.log(`${failures} check(s) failed`);
	process.exit(1);
}
console.log("all checks passed");
