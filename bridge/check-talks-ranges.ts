/**
 * Byte-range check for rendered talks.
 *
 * Range parsing is where an off-by-one lives, silently: a player that gets one
 * byte short, or a 206 with the wrong Content-Range, skips or stutters in the
 * middle of a 30 minute file and says nothing. This starts an HTTP server on a
 * loopback port, serves a known file through the same `serveFile` the bridge
 * uses, and asserts the three cases that matter: a whole GET, a hundred-byte
 * slice, and a range past the end.
 *
 * Run it directly, node strips the types:
 *   node bridge/check-talks-ranges.ts
 */

import * as fs from "node:fs";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import { serveFile } from "./lib/talks.ts";

const SIZE = 1000;
// Deterministic content, so a slice can be compared byte for byte rather than
// only by length.
const CONTENT = Buffer.alloc(SIZE);
for (let i = 0; i < SIZE; i++) CONTENT[i] = i % 256;

let failures = 0;

function check(name: string, ok: boolean, detail: string): void {
	if (ok) {
		console.log(`ok   ${name}`);
	} else {
		failures += 1;
		console.log(`FAIL ${name}: ${detail}`);
	}
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "pi-talks-range-"));
const file = path.join(dir, "sample.mp3");
fs.writeFileSync(file, CONTENT);

const server = http.createServer((req, res) => {
	serveFile(req, res, file, "audio/mpeg");
});

await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
if (!address || typeof address === "string") {
	console.log("FAIL could not bind a loopback port");
	process.exit(1);
}
const base = `http://127.0.0.1:${address.port}/api/talks/sample.mp3`;

// 1. A whole GET returns the whole file, with ranges advertised.
{
	const res = await fetch(base);
	const body = Buffer.from(await res.arrayBuffer());
	check("full GET status is 200", res.status === 200, `status ${res.status}`);
	check("full GET is the whole file", body.length === SIZE, `${body.length} bytes, want ${SIZE}`);
	check("full GET content matches", body.equals(CONTENT), "body differs from the file");
	check(
		"full GET advertises ranges",
		res.headers.get("accept-ranges") === "bytes",
		`accept-ranges ${res.headers.get("accept-ranges")}`,
	);
}

// 2. bytes=0-99 is exactly 100 bytes, 206, with a correct Content-Range.
{
	const res = await fetch(base, { headers: { Range: "bytes=0-99" } });
	const body = Buffer.from(await res.arrayBuffer());
	check("range 0-99 status is 206", res.status === 206, `status ${res.status}`);
	check("range 0-99 is exactly 100 bytes", body.length === 100, `${body.length} bytes, want 100`);
	check(
		"range 0-99 Content-Range",
		res.headers.get("content-range") === `bytes 0-99/${SIZE}`,
		`content-range ${res.headers.get("content-range")}`,
	);
	check("range 0-99 content matches the head", body.equals(CONTENT.subarray(0, 100)), "body differs from head");
}

// 3. A range that starts past the end has nothing to give.
{
	const res = await fetch(base, { headers: { Range: `bytes=${SIZE}-` } });
	await res.arrayBuffer();
	check("range past the end status is 416", res.status === 416, `status ${res.status}`);
	check(
		"range past the end Content-Range",
		res.headers.get("content-range") === `bytes */${SIZE}`,
		`content-range ${res.headers.get("content-range")}`,
	);
}

server.close();
fs.rmSync(dir, { recursive: true, force: true });

console.log(failures === 0 ? "PASS all range checks" : `FAILED ${failures} check(s)`);
process.exit(failures === 0 ? 0 : 1);
