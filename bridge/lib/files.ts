/**
 * The files library: any file a pi session leaves in the agent's talks
 * directory, not only the rendered talk MP3s.
 *
 * A talk is still described by its metadata record (see talks.ts). Everything
 * else is just a file on disk, so the library is a directory read plus an
 * extension lookup: there is no database, no index and no registration for a
 * media or text file. A session that wants a file to show up copies it into
 * `<agent dir>/talks`, which is the same directory the renderer writes into, so
 * a rendered talk and a hand-dropped report sit in one list. The name of a file
 * is what the app downloads it by, so every name that can be listed is also a
 * name that can be served, and isSafeFileName is the one place that shape is
 * decided.
 *
 * The kind (audio, video, image, text, other) is inferred from the extension
 * alone. The app decides what to do with a kind; the bridge only names it. A
 * kind the app cannot draw is still listed, because hiding a produced file is
 * worse than saying it cannot be shown.
 *
 * This module imports talks.ts but not the other way round, so there is no
 * cycle, and it imports no pi runtime so the list and the download route can be
 * exercised by a plain node script.
 */

import * as fs from "node:fs";
import * as path from "node:path";
import type * as http from "node:http";
import { listTalks, prettifyTalkTitle, readableTalkError, serveFile, talksDir, type FileServeResult, type TalkRecord } from "./talks.ts";

export type FileKind = "audio" | "video" | "image" | "text" | "other";

/** One library entry, either a rendered talk or a bare file in the directory. */
export interface FileRecord {
	/** The row key: a talk's id, or a bare file's whole name. */
	id: string;
	/** The on-disk name, and the name the download route serves. */
	name: string;
	title: string;
	kind: FileKind;
	ext: string;
	bytes: number | null;
	state: "rendering" | "ready" | "failed";
	createdAt: string;
	durationSeconds: number | null;
	durationEstimated: boolean;
	voice: string | null;
	error: string | null;
}

/** Extensions with a known kind. Anything else lists as "other", never hidden. */
const KIND_BY_EXT: Record<string, FileKind> = {
	mp3: "audio",
	m4a: "audio",
	aac: "audio",
	wav: "audio",
	ogg: "audio",
	oga: "audio",
	opus: "audio",
	flac: "audio",
	weba: "audio",
	mp4: "video",
	m4v: "video",
	webm: "video",
	mkv: "video",
	mov: "video",
	ogv: "video",
	png: "image",
	jpg: "image",
	jpeg: "image",
	gif: "image",
	webp: "image",
	bmp: "image",
	heic: "image",
	heif: "image",
	txt: "text",
	md: "text",
	markdown: "text",
	json: "text",
	csv: "text",
	log: "text",
	html: "text",
	htm: "text",
	xml: "text",
	yaml: "text",
	yml: "text",
	tsv: "text",
};

/** A media type per extension, so a download carries something a player can use. */
const CONTENT_TYPE_BY_EXT: Record<string, string> = {
	mp3: "audio/mpeg",
	m4a: "audio/mp4",
	aac: "audio/aac",
	wav: "audio/wav",
	ogg: "audio/ogg",
	oga: "audio/ogg",
	opus: "audio/ogg",
	flac: "audio/flac",
	weba: "audio/webm",
	mp4: "video/mp4",
	m4v: "video/mp4",
	webm: "video/webm",
	mkv: "video/x-matroska",
	mov: "video/quicktime",
	ogv: "video/ogg",
	png: "image/png",
	jpg: "image/jpeg",
	jpeg: "image/jpeg",
	gif: "image/gif",
	webp: "image/webp",
	bmp: "image/bmp",
	heic: "image/heic",
	heif: "image/heif",
	txt: "text/plain; charset=utf-8",
	md: "text/markdown; charset=utf-8",
	markdown: "text/markdown; charset=utf-8",
	json: "application/json; charset=utf-8",
	csv: "text/csv; charset=utf-8",
	log: "text/plain; charset=utf-8",
	html: "text/html; charset=utf-8",
	htm: "text/html; charset=utf-8",
	xml: "application/xml; charset=utf-8",
	yaml: "text/yaml; charset=utf-8",
	yml: "text/yaml; charset=utf-8",
	tsv: "text/tab-separated-values; charset=utf-8",
};

/**
 * The one shape that may be listed and served: a leading letter or digit, then
 * word characters, dots, spaces and a few punctuation marks, bounded in length.
 * The leading-character rule is what stops a hidden file, and the missing `/`
 * and `\` are what stop a name walking out of the directory.
 */
const SAFE_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,127}$/;

export function isSafeFileName(name: string): boolean {
	return SAFE_NAME_RE.test(name);
}

/** The lowercased extension without its dot, or "" when there is none. */
export function extensionOf(name: string): string {
	const dot = name.lastIndexOf(".");
	// A leading dot is a hidden file, not an extension, so `dot > 0` covers both.
	if (dot <= 0 || dot === name.length - 1) return "";
	return name.slice(dot + 1).toLowerCase();
}

export function fileKindForExt(ext: string): FileKind {
	return KIND_BY_EXT[ext] ?? "other";
}

export function contentTypeForName(name: string): string {
	return CONTENT_TYPE_BY_EXT[extensionOf(name)] ?? "application/octet-stream";
}

/** A talk's audio as a library row. The mp3 name is the download key. */
function fileFromTalk(talk: TalkRecord): FileRecord {
	return {
		id: talk.id,
		name: `${talk.id}.mp3`,
		title: talk.title,
		kind: "audio",
		ext: "mp3",
		bytes: talk.bytes,
		state: talk.state,
		createdAt: talk.createdAt,
		durationSeconds: talk.durationSeconds,
		durationEstimated: talk.durationEstimated,
		voice: talk.voice || null,
		error: talk.error,
	};
}

/**
 * Every file in the library directory, newest first, plus a talk still rendering
 * that has no file yet. A talk's own script and metadata are not separate rows:
 * they belong to the talk, which is the audio row. A directory read that fails
 * is an empty library, and a file whose name cannot be served is skipped, so
 * every row here is one the download route will answer.
 */
export function listFiles(): FileRecord[] {
	const talks = listTalks();
	const talkIds = new Set(talks.map((talk) => talk.id));
	const records = talks.map(fileFromTalk);

	let names: string[];
	try {
		names = fs.readdirSync(talksDir());
	} catch {
		// The directory does not exist until the first render; no files is the
		// same answer either way.
		return newestFirst(records);
	}

	for (const name of names) {
		if (!isSafeFileName(name)) continue;
		const ext = extensionOf(name);
		// No extension means nothing to act on, and `.part` is a download in
		// progress rather than a produced file.
		if (ext === "" || ext === "part") continue;
		const base = name.slice(0, name.length - ext.length - 1);
		// The three siblings of a talk are the talk: the audio is its row, and the
		// script and metadata are its internals, so none of them is listed again.
		if (talkIds.has(base) && (ext === "mp3" || ext === "txt" || ext === "json")) continue;
		let stat: fs.Stats;
		try {
			stat = fs.statSync(path.join(talksDir(), name));
		} catch {
			continue;
		}
		if (!stat.isFile()) continue;
		const at = stat.mtime.toISOString();
		records.push({
			id: name,
			name,
			title: prettifyTalkTitle(base),
			kind: fileKindForExt(ext),
			ext,
			bytes: stat.size,
			state: "ready",
			createdAt: at,
			durationSeconds: null,
			durationEstimated: false,
			voice: null,
			error: null,
		});
	}
	return newestFirst(records);
}

function newestFirst(records: FileRecord[]): FileRecord[] {
	return records.sort((left, right) =>
		left.createdAt < right.createdAt ? 1 : left.createdAt > right.createdAt ? -1 : 0,
	);
}

/** The same string rule the rest of the bridge uses for an untrusted field. */
const MAX_FIELD = 4000;

function cut(value: string | null): string | null {
	if (value === null) return null;
	return value.length > MAX_FIELD ? value.slice(0, MAX_FIELD) : value;
}

/**
 * One library row, trimmed. Only a title or an error a hand-edited file could
 * stretch, so both are capped here. The name is already bounded by
 * isSafeFileName, which is what keeps it out of the trim.
 */
export function fileSummary(record: FileRecord) {
	return {
		id: record.id,
		name: record.name,
		title: record.title.length > MAX_FIELD ? `${record.title.slice(0, MAX_FIELD)}…` : record.title,
		kind: record.kind,
		ext: record.ext,
		state: record.state,
		bytes: record.bytes,
		createdAt: record.createdAt,
		durationSeconds: record.durationSeconds,
		durationEstimated: record.durationEstimated,
		voice: record.voice,
		error: cut(readableTalkError(record.error)),
	};
}

/**
 * The whole library. Rows are trimmed, the list is not, for the same reason
 * talksPayload does it that way: shrink() caps an array at MAX_ARRAY, and a
 * library past 40 files would lose its oldest entries silently. The caller must
 * carry this whole rather than pass it through shrink().
 */
export function filePayload() {
	return { files: listFiles().map(fileSummary) };
}

/** A 404 in the shape every other missing thing in the bridge uses. */
function notFound(res: http.ServerResponse): FileServeResult {
	const body = JSON.stringify({ error: "no such file" });
	res.writeHead(404, {
		"content-type": "application/json; charset=utf-8",
		"content-length": Buffer.byteLength(body),
		"cache-control": "no-store",
	});
	res.end(body);
	return { status: 404 };
}

/**
 * Serve one library file by name, with the range support a long audio or video
 * file needs. A name that cannot be a valid file name, or names nothing, is a
 * 404 rather than anything that reaches the filesystem. This is the whole
 * download path, so the route and the check script exercise the same code.
 */
export function serveLibraryFile(req: http.IncomingMessage, res: http.ServerResponse, name: string): FileServeResult {
	if (!isSafeFileName(name)) return notFound(res);
	return serveFile(req, res, path.join(talksDir(), name), contentTypeForName(name), "file is not available");
}
