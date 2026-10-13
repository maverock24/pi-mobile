/**
 * Files library check.
 *
 * The library is a directory read plus the same `serveLibraryFile` route the
 * bridge uses, so this starts an HTTP server on a loopback port and fetches the
 * list and one file through it. It asserts the two things that matter for the
 * rename: the existing `bees.mp3` still appears as an audio row, and a row can
 * actually be downloaded by name with byte ranges.
 *
 * It reads the real agent directory, so it proves the live library rather than a
 * fixture. Run it directly, node strips the types:
 *   node bridge/check-files.ts
 */

import * as http from "node:http";
import { filePayload, serveLibraryFile } from "./lib/files.ts";

let failures = 0;

function check(name: string, ok: boolean, detail: string): void {
	if (ok) {
		console.log(`ok   ${name}`);
	} else {
		failures += 1;
		console.log(`FAIL ${name}: ${detail}`);
	}
}

const server = http.createServer((req, res) => {
	const url = new URL(req.url || "/", "http://localhost");
	if (req.method === "GET" && url.pathname === "/api/files") {
		const body = JSON.stringify(filePayload());
		res.writeHead(200, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(body) });
		res.end(body);
		return;
	}
	const match = /^\/api\/files\/(.+)$/.exec(url.pathname);
	if (match) {
		serveLibraryFile(req, res, decodeURIComponent(match[1]));
		return;
	}
	res.writeHead(404, { "content-type": "application/json" });
	res.end(JSON.stringify({ error: "not found" }));
});

await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
if (!address || typeof address === "string") {
	console.log("FAIL could not bind a loopback port");
	process.exit(1);
}
const base = `http://127.0.0.1:${address.port}`;

const listed = (await (await fetch(`${base}/api/files`)).json()) as { files?: Array<{ name: string; kind: string; state: string }> };
const files = listed.files ?? [];
console.log(`library holds ${files.length} row(s): ${files.map((file) => file.name).join(", ") || "(none)"}`);
check("the list is a non-empty array", files.length > 0, `got ${files.length} rows`);

const bees = files.find((file) => file.name === "bees.mp3");
check("bees.mp3 is listed", bees !== undefined, "bees.mp3 is not in the library");
check("bees.mp3 is audio and ready", bees?.kind === "audio" && bees?.state === "ready", `kind ${bees?.kind}, state ${bees?.state}`);

// The whole file, then a range through the same route a phone player uses.
if (bees) {
	const full = await fetch(`${base}/api/files/bees.mp3`);
	const body = await full.arrayBuffer();
	check("bees.mp3 downloads with 200", full.status === 200, `status ${full.status}`);
	check("bees.mp3 is non-empty", body.byteLength > 0, `${body.byteLength} bytes`);
	check(
		"bees.mp3 advertises ranges",
		full.headers.get("accept-ranges") === "bytes",
		`accept-ranges ${full.headers.get("accept-ranges")}`,
	);
	check(
		"bees.mp3 is audio/mpeg",
		full.headers.get("content-type") === "audio/mpeg",
		`content-type ${full.headers.get("content-type")}`,
	);

	const ranged = await fetch(`${base}/api/files/bees.mp3`, { headers: { Range: "bytes=0-99" } });
	const slice = await ranged.arrayBuffer();
	check("range 0-99 status is 206", ranged.status === 206, `status ${ranged.status}`);
	check("range 0-99 is exactly 100 bytes", slice.byteLength === 100, `${slice.byteLength} bytes, want 100`);
}

// A name that could walk out of the directory must not be served.
const escape = await fetch(`${base}/api/files/${encodeURIComponent("../bees.mp3")}`);
await escape.arrayBuffer();
check("a traversal name is refused", escape.status === 404, `status ${escape.status}`);

server.close();
console.log(failures === 0 ? "PASS all files checks" : `FAILED ${failures} check(s)`);
process.exit(failures === 0 ? 0 : 1);
