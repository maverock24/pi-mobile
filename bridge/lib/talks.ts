/**
 * Storage and HTTP serving for generated talks.
 *
 * A talk is a script that a separately installed Kokoro renderer turns into an
 * MP3. This module owns where a talk lives on disk, how its metadata is written,
 * how the MP3 is served with byte ranges, and which renderer a talk would use. It
 * deliberately imports no pi runtime, so all of that can be exercised by a plain
 * node script.
 *
 * Kokoro is never vendored or bundled here: the bridge only invokes a locally
 * installed renderer as a subprocess. Kokoro is the only engine, because the
 * human asked for it by name and a talk quietly rendered by something else is
 * worse than an error, so nothing here falls back to another engine.
 *
 * Layout under <agent dir>/talks: <id>.json (metadata), <id>.txt (the script the
 * talk was rendered from), <id>.mp3 (the audio). One directory keeps a delete a
 * single unlink of three siblings, and a listing a single readdir.
 */

import { spawnSync } from "node:child_process";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";

export type TalkState = "rendering" | "ready" | "failed";

export interface TalkRecord {
	id: string;
	title: string;
	createdAt: string;
	updatedAt: string;
	state: TalkState;
	/** The script the talk was rendered from, copied into the talks directory. */
	scriptPath: string;
	mp3Path: string;
	/** File size once the render lands, null while rendering or after a failure. */
	bytes: number | null;
	/** Approximate spoken length in seconds, from the script word count. */
	durationSeconds: number | null;
	durationEstimated: boolean;
	voice: string;
	/** A single readable line on failure, never a stack trace. */
	error: string | null;
}

/**
 * The pi agent directory. This mirrors install.sh's `PI_AGENT_DIR:-$HOME/.pi/agent`
 * so the bridge reads the same directory the installer wrote into.
 */
export function agentDir(): string {
	const configured = process.env.PI_AGENT_DIR;
	if (configured && configured.trim()) {
		return configured.trim().replace(/^~(?=\/|$)/, os.homedir());
	}
	return path.join(os.homedir(), ".pi", "agent");
}

export function talksDir(): string {
	return path.join(agentDir(), "talks");
}

/** The Kokoro voice used when the caller does not name one. */
export const DEFAULT_TALK_VOICE = "af_heart";

/**
 * The Python interpreter that has Kokoro importable. The install on this machine
 * is a virtualenv under ~/.local/share/kokoro, and the override exists for a
 * second render host whose install lives elsewhere.
 */
export function kokoroPythonPath(): string {
	const configured = process.env.PI_REMOTE_KOKORO_PYTHON;
	if (configured && configured.trim()) return configured.trim();
	return path.join(os.homedir(), ".local", "share", "kokoro", "venv", "bin", "python");
}

/**
 * The renderer script. install.sh copies it beside the talk skill, so the
 * installed layout is <agent dir>/skills/talk/render-kokoro.py. The override
 * exists so a check can point at a copy without installing first.
 */
export function kokoroScriptPath(): string {
	const configured = process.env.PI_REMOTE_KOKORO;
	if (configured && configured.trim()) return configured.trim();
	return path.join(agentDir(), "skills", "talk", "render-kokoro.py");
}

/**
 * The exact argv that renders one script to one MP3. The renderer chunks a long
 * script itself and rejoins at sentence and paragraph boundaries, so the bridge
 * hands over the whole script and one output path rather than a piece at a time.
 */
export function kokoroCommand(script: string, mp3: string, voice: string): { python: string; args: string[] } {
	return {
		python: kokoroPythonPath(),
		args: [kokoroScriptPath(), script, mp3, voice],
	};
}

/**
 * Why a Kokoro render cannot start, or undefined when it can. A missing renderer
 * and a broken one are the same failure to the human, so both come back as one
 * actionable sentence naming the path that was checked, never a stack trace. The
 * probe imports Kokoro, which proves the interpreter still has it; that loads no
 * model and touches no network.
 */
export function kokoroProblem(): string | undefined {
	const renderer = kokoroScriptPath();
	if (!fs.existsSync(renderer)) {
		return `Kokoro is required to render a talk but its renderer is missing at ${renderer}. Run bridge/install.sh, then try again.`;
	}
	const python = kokoroPythonPath();
	if (!fs.existsSync(python)) {
		return `Kokoro is required to render a talk but its Python is missing at ${python}. Install Kokoro there, then try again.`;
	}
	const probe = spawnSync(python, ["-c", "import kokoro"], { encoding: "utf8", timeout: 60_000 });
	if (probe.error || probe.status !== 0) {
		const tail = `${probe.stderr || ""}`.split("\n").map((line) => line.trim()).filter(Boolean).pop();
		const detail = (tail || probe.error?.message || "unknown error").slice(0, 200);
		return `Kokoro is installed at ${python} but could not be imported (${detail}). Reinstall it, then try again.`;
	}
	return undefined;
}

export function newTalkId(): string {
	return crypto.randomBytes(8).toString("hex");
}

/** Ids reach the filesystem, so only the shape this module mints is accepted. */
export function isSafeTalkId(id: string): boolean {
	return /^[A-Za-z0-9_-]{1,64}$/.test(id);
}

export function talkPaths(id: string): { meta: string; mp3: string; script: string } {
	return {
		meta: path.join(talksDir(), `${id}.json`),
		mp3: path.join(talksDir(), `${id}.mp3`),
		script: path.join(talksDir(), `${id}.txt`),
	};
}

function isTalkState(value: unknown): value is TalkState {
	return value === "rendering" || value === "ready" || value === "failed";
}

/** Write metadata so a reader never sees a half-written record. */
export function saveTalk(record: TalkRecord): void {
	const target = talkPaths(record.id).meta;
	fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
	const temp = `${target}.tmp`;
	fs.writeFileSync(temp, `${JSON.stringify(record)}\n`, { mode: 0o600 });
	fs.renameSync(temp, target);
}

/**
 * A talk inferred from an MP3 that has no metadata record. The filename is the
 * only description that is left, so the id is the file's basename and the title
 * is that name prettied up. Size and time come from the file itself; the spoken
 * length is not guessed, since reading it would mean decoding MPEG frames.
 */
function orphanTalk(id: string): TalkRecord | undefined {
	const mp3 = talkPaths(id).mp3;
	let stat: fs.Stats;
	try {
		stat = fs.statSync(mp3);
	} catch {
		return undefined;
	}
	if (!stat.isFile()) return undefined;
	const at = stat.mtime.toISOString();
	return {
		id,
		title: prettifyTalkTitle(id),
		createdAt: at,
		updatedAt: at,
		state: "ready",
		scriptPath: talkPaths(id).script,
		mp3Path: mp3,
		bytes: stat.size,
		durationSeconds: null,
		durationEstimated: false,
		voice: "",
		error: null,
	};
}

/** "bees" or "great_lecture-2" as a readable title: "Bees", "Great Lecture 2". */
export function prettifyTalkTitle(id: string): string {
	const words = id.replace(/[-_]+/g, " ").split(" ").filter(Boolean);
	if (words.length === 0) return "Talk";
	return words.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(" ");
}

/**
 * The talk named [id]: its metadata record when one reads, otherwise the MP3
 * alone. A file that an older bridge or a render outside the API left without a
 * record is still playable, so the record is preferred and the bare file is the
 * fallback rather than a 404.
 */
export function readTalk(id: string): TalkRecord | undefined {
	if (!isSafeTalkId(id)) return undefined;
	try {
		const raw = JSON.parse(fs.readFileSync(talkPaths(id).meta, "utf8")) as TalkRecord;
		if (raw && typeof raw === "object" && isTalkState(raw.state)) return raw;
	} catch {
		// Fall through: a record that will not read does not hide the audio beside it.
	}
	return orphanTalk(id);
}

/**
 * Every talk, newest first. A corrupt record is skipped rather than failing the
 * whole list, so one bad file cannot hide the rest of the library. An MP3 with
 * no record of its own is listed too: a library that hides a playable file
 * because a record is missing is the bug this fallback exists to avoid. One id
 * is one row, and the metadata record is authoritative when both are present.
 */
export function listTalks(): TalkRecord[] {
	let names: string[];
	try {
		names = fs.readdirSync(talksDir());
	} catch {
		// The directory does not exist until the first render; an empty library is
		// the same answer either way.
		return [];
	}
	// One row per id. A `.json` record and a `.mp3` of the same id are the same
	// talk: readTalk prefers the record, so reading both names cannot double it.
	const byId = new Map<string, TalkRecord>();
	for (const name of names) {
		const id = name.endsWith(".json")
			? name.slice(0, -".json".length)
			: name.endsWith(".mp3")
				? name.slice(0, -".mp3".length)
				: undefined;
		if (id === undefined || !isSafeTalkId(id)) continue;
		const record = readTalk(id);
		if (record) byId.set(id, record);
	}
	const talks = [...byId.values()];
	talks.sort((left, right) => (left.createdAt < right.createdAt ? 1 : left.createdAt > right.createdAt ? -1 : 0));
	return talks;
}

/** Removes the mp3, the script copy and the metadata. True when a talk was there. */
export function removeTalk(id: string): boolean {
	if (!isSafeTalkId(id)) return false;
	const paths = talkPaths(id);
	const existed = fs.existsSync(paths.meta);
	for (const file of [paths.mp3, paths.script, paths.meta]) {
		try {
			fs.rmSync(file, { force: true });
		} catch {
			// A file that is already gone is the goal, not an error.
		}
	}
	return existed;
}

/** Spoken length from the word count, at the 150 words per minute Kokoro reads. */
export function estimateDurationSeconds(script: string): number {
	const words = script.trim().split(/\s+/).filter(Boolean).length;
	if (words === 0) return 0;
	return Math.round((words / 150) * 60);
}

export type RangeResult =
	| { kind: "none" }
	| { kind: "invalid" }
	| { kind: "range"; start: number; end: number };

/**
 * Parse a single-byte-range `Range` header. Returns "none" when the caller sent
 * no range, "invalid" for anything this server will not satisfy (a malformed
 * header, a multi-range, or a start at or past the end), and the inclusive byte
 * offsets otherwise. The caller answers "invalid" with 416 and the whole file
 * with a 200, so the off-by-one that this function owns is the only place a
 * range can go wrong.
 */
export function parseRangeHeader(header: string | undefined, size: number): RangeResult {
	if (header === undefined || header.trim() === "") return { kind: "none" };
	// One range only. A phone player never sends a multi-range, and refusing is
	// more honest than guessing which range it wanted.
	const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
	if (!match) return { kind: "invalid" };
	const startText = match[1];
	const endText = match[2];
	if (startText === "" && endText === "") return { kind: "invalid" };
	if (size <= 0) return { kind: "invalid" };
	if (startText === "") {
		// A suffix range: the last N bytes. `bytes=-0` asks for nothing.
		const suffix = Number(endText);
		if (!Number.isSafeInteger(suffix) || suffix <= 0) return { kind: "invalid" };
		return { kind: "range", start: Math.max(0, size - suffix), end: size - 1 };
	}
	const start = Number(startText);
	if (!Number.isSafeInteger(start) || start >= size) return { kind: "invalid" };
	let end = endText === "" ? size - 1 : Number(endText);
	if (!Number.isSafeInteger(end) || end < start) return { kind: "invalid" };
	if (end > size - 1) end = size - 1;
	return { kind: "range", start, end };
}

export interface FileServeResult {
	status: number;
	start?: number;
	end?: number;
	bytes?: number;
}

/**
 * Serve a file with byte-range support. The status, the inclusive offsets and
 * the byte count it answered are returned so a caller can audit a download
 * without re-reading the headers it just wrote.
 */
export function serveFile(
	req: http.IncomingMessage,
	res: http.ServerResponse,
	file: string,
	contentType: string,
	missingMessage = "talk is not available",
): FileServeResult {
	let size: number;
	try {
		const stat = fs.statSync(file);
		if (!stat.isFile()) throw new Error("not a file");
		size = stat.size;
	} catch {
		const body = JSON.stringify({ error: missingMessage });
		res.writeHead(404, {
			"content-type": "application/json; charset=utf-8",
			"content-length": Buffer.byteLength(body),
			"cache-control": "no-store",
		});
		res.end(body);
		return { status: 404 };
	}

	const range = parseRangeHeader(typeof req.headers.range === "string" ? req.headers.range : undefined, size);
	if (range.kind === "invalid") {
		res.writeHead(416, {
			"content-type": "text/plain; charset=utf-8",
			"content-range": `bytes */${size}`,
			"accept-ranges": "bytes",
			"cache-control": "no-store",
		});
		res.end();
		return { status: 416 };
	}

	if (range.kind === "none") {
		res.writeHead(200, {
			"content-type": contentType,
			"content-length": size,
			"accept-ranges": "bytes",
			"cache-control": "no-store",
		});
		if (req.method === "HEAD") {
			res.end();
		} else {
			fs.createReadStream(file).pipe(res);
		}
		return { status: 200, start: 0, end: size - 1, bytes: size };
	}

	const { start, end } = range;
	const length = end - start + 1;
	res.writeHead(206, {
		"content-type": contentType,
		"content-length": length,
		"content-range": `bytes ${start}-${end}/${size}`,
		"accept-ranges": "bytes",
		"cache-control": "no-store",
	});
	if (req.method === "HEAD") {
		res.end();
	} else {
		fs.createReadStream(file, { start, end }).pipe(res);
	}
	return { status: 206, start, end, bytes: length };
}
