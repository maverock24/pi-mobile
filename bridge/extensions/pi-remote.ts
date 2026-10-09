/**
 * Pi Remote — control the running pi session from a phone.
 *
 * Starts a small HTTP + SSE server inside the pi process itself, so a remote
 * client can read state, send prompts, abort turns, and follow live output
 * without touching the terminal UI.
 *
 * Endpoints (all require `Authorization: Bearer <token>`):
 *   GET  /api/health    - liveness probe
 *   GET  /api/state     - session, model, idle/busy, pending queue, bound address
 *   GET  /api/history   - messages on the active branch (?limit=N)
 *   GET  /api/search    - message entries matching ?q= (case-insensitive)
 *   GET  /api/commands  - slash commands, prompt templates and skills, so the
 *                         phone can offer what it can dispatch
 *   GET  /api/events    - SSE stream of agent/tool/message events
 *   POST /api/prompt    - {"text": "...", "deliverAs": "steer"|"followUp"}
 *   POST /api/abort     - abort the current agent run
 *   GET  /api/release/latest.json - update manifest for the app, served here
 *                                  instead of a public GitHub release
 *   GET  /api/release/apk         - the signed APK described by that manifest
 *   GET  /api/question  - pending question widget, if one is open
 *   POST /api/answer    - answer it: {"questionId": "...", "value": "...",
 *                         "custom": false, "cancel": false, "submit": false}
 *
 * While a session serves the bridge it marks its own herdr pane with a
 * "remote ●/○" badge (● means a phone is attached), so you can see at a glance
 * which pane the app is talking to.
 *
 * One pi session serves one bridge address. The successful bind is the only
 * authority: a session writes a lease keyed by address only after it binds, and
 * its heartbeat refreshes only that lease. `/pi-remote` writes a takeover record
 * naming itself, every session watches the state directory for it, and the
 * serving session drops its server at once so the newcomer can bind.
 *
 * POST /api/prompt, /api/abort and /api/answer accept an optional sessionId;
 * when it is present and does not match the serving session the bridge answers
 * 409 and changes nothing.
 *
 * Two panes may serve two addresses, and a takeover on one never touches the
 * other. Records live in ~/.config/pi-remote next to the tokens.
 *
 * Config (env):
 *   PI_REMOTE_HOST            bind address (default 100.78.153.24, tailnet IP)
 *   PI_REMOTE_PORT            port (default 8787)
 *   PI_REMOTE_TOKEN_FILE      legacy single-token file, migrated on first run
 *                             (tokens now live in tokens.json next to it)
 *   PI_REMOTE_DISABLE         "1" skips starting the bridge entirely
 *   PI_REMOTE_ALLOW_NON_TUI   "1" lets a non-interactive session serve (tests)
 *   PI_REMOTE_HEARTBEAT_MS    heartbeat interval (default 10000)
 *   PI_REMOTE_STALE_MS        ownership staleness window (default 30000)
 *
 * Never expose this port outside a network you control: the bearer token is the
 * only application-level gate, and one token controls the whole session.
 */

import { spawn, spawnSync } from "node:child_process";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as http from "node:http";
import { createConnection } from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { readPending, remoteDir, writeAnswer } from "../lib/remote-question.ts";
import {
	decideState,
	isTakeoverFile,
	leaseFileName,
	leaseIsStale,
	mayRefreshLease,
	takeoverFileName,
	takeoverIsValid,
	type LeaseRecord,
	type TakeoverRecord,
} from "../lib/lease.ts";

const DEFAULT_HOST = "100.78.153.24";
const FALLBACK_HOST = "127.0.0.1";
const DEFAULT_PORT = 8787;
const PAIR_TTL_MS = 120_000;
const PAIR_MAX_ATTEMPTS = 5;
const PAIR_LOCKOUT_MS = 60_000;
/** Rule 5: how long `/pi-remote` and `/pair` retry a held address before giving up. */
const TAKEOVER_WINDOW_MS = 5000;
const PAIR_WIDGET_ID = "pi-remote-pair";
const MAX_STRING = 4000;
const MAX_ARRAY = 40;
const MAX_DEPTH = 8;

/** How much of a matched message a search hit carries around the match. */
const SNIPPET_BEFORE = 40;
const SNIPPET_AFTER = 120;
const SNIPPET_MAX = 200;

function tokenPath(): string {
	return process.env.PI_REMOTE_TOKEN_FILE || path.join(os.homedir(), ".config", "pi-remote", "token");
}

/** Every record for an address lives in the token's directory. */
function stateDir(): string {
	return path.dirname(tokenPath());
}

function configuredHost(): string {
	return process.env.PI_REMOTE_HOST || DEFAULT_HOST;
}

function configuredPort(): number {
	return Number(process.env.PI_REMOTE_PORT || DEFAULT_PORT);
}

/** One lease per address. The old single owner.json is ignored, never migrated. */
function leasePath(host = configuredHost(), port = configuredPort()): string {
	return path.join(stateDir(), leaseFileName(host, port));
}

/** One takeover record per address, written by `/pi-remote` and `/pair`. */
function takeoverPath(host = configuredHost(), port = configuredPort()): string {
	return path.join(stateDir(), takeoverFileName(host, port));
}

/** Write JSON so no reader ever sees a half-written record. */
function writeAtomic(target: string, payload: unknown): void {
	try {
		fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
		const temp = `${target}.tmp`;
		fs.writeFileSync(temp, `${JSON.stringify(payload)}\n`, { mode: 0o600 });
		fs.renameSync(temp, target);
	} catch {
		// records are best effort; the bind itself is the authority
	}
}

type TokenEntry = { label: string; token: string; created: string };

function tokensPath(): string {
	return path.join(path.dirname(tokenPath()), "tokens.json");
}

function writeTokens(entries: TokenEntry[]): void {
	const file = tokensPath();
	fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
	const temp = `${file}.tmp`;
	fs.writeFileSync(temp, `${JSON.stringify(entries, null, 1)}\n`, { mode: 0o600 });
	fs.renameSync(temp, file);
	fs.chmodSync(file, 0o600);
}

function newToken(): string {
	return crypto.randomBytes(32).toString("hex");
}

/**
 * Tokens live in tokens.json, one per device, so a phone can be revoked without
 * disturbing anything else. A legacy token file is migrated on first read and
 * removed, keeping one source of truth.
 */
function loadTokens(): TokenEntry[] {
	try {
		const parsed = JSON.parse(fs.readFileSync(tokensPath(), "utf8"));
		if (Array.isArray(parsed)) {
			const entries = parsed
				.filter((entry) => typeof entry?.token === "string" && entry.token.length >= 32)
				.map((entry) => ({
					label: String(entry.label || "unnamed"),
					token: String(entry.token),
					created: String(entry.created || ""),
				}));
			if (entries.length > 0) {
				return entries;
			}
		}
	} catch {
		// missing or unreadable: try the legacy file below
	}
	let legacy = "";
	try {
		legacy = fs.readFileSync(tokenPath(), "utf8").trim();
	} catch {
		legacy = "";
	}
	if (legacy.length >= 32) {
		const migrated: TokenEntry[] = [{ label: "phone", token: legacy, created: new Date().toISOString() }];
		writeTokens(migrated);
		try {
			fs.rmSync(tokenPath(), { force: true });
		} catch {
			// left in place only if it cannot be removed
		}
		return migrated;
	}
	return [];
}

let tokenCache: { at: number; entries: TokenEntry[] } = { at: 0, entries: [] };

/** Re-reads at most once every two seconds, so rotation applies without a restart. */
function tokens(): TokenEntry[] {
	const now = Date.now();
	if (now - tokenCache.at > 2000) {
		tokenCache = { at: now, entries: loadTokens() };
	}
	return tokenCache.entries;
}

/** Authenticates a request and returns the matching device label. */
function matchToken(provided: string): TokenEntry | undefined {
	if (!provided) {
		return undefined;
	}
	const candidate = Buffer.from(provided);
	for (const entry of tokens()) {
		const expected = Buffer.from(entry.token);
		if (expected.length === candidate.length && crypto.timingSafeEqual(expected, candidate)) {
			return entry;
		}
	}
	return undefined;
}

// ---------- audit log and auth backoff ----------

/** Where pi-remote-release-sync puts the newest build pulled from the repo. */
function releaseDir(): string {
	return path.join(remoteDir(), "release");
}

/**
 * The manifest as published by CI, with the APK url rewritten to a relative path
 * on this bridge: the app never needs to reach GitHub, and a private repo works.
 */
function readReleaseManifest(): Record<string, unknown> | null {
	try {
		const raw = JSON.parse(fs.readFileSync(path.join(releaseDir(), "latest.json"), "utf8"));
		if (!raw || typeof raw !== "object") {
			return null;
		}
		return { ...(raw as Record<string, unknown>), url: "/api/release/apk" };
	} catch {
		return null;
	}
}

function auditPath(): string {
	return path.join(remoteDir(), "audit.log");
}

function audit(event: Record<string, unknown>): void {
	try {
		fs.mkdirSync(remoteDir(), { recursive: true, mode: 0o700 });
		const line = `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`;
		fs.appendFileSync(auditPath(), line, { mode: 0o600 });
	} catch {
		// auditing must never break a request
	}
}

/** Trim payloads so a phone does not have to swallow base64 images or 200KB tool dumps. */
function shrink(value: unknown, depth = 0): unknown {
	if (depth > MAX_DEPTH) {
		return "[truncated]";
	}
	if (typeof value === "string") {
		return value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}… [${value.length} chars]` : value;
	}
	if (Array.isArray(value)) {
		const head = value.slice(0, MAX_ARRAY).map((item) => shrink(item, depth + 1));
		return value.length > MAX_ARRAY ? [...head, `[… ${value.length - MAX_ARRAY} more]`] : head;
	}
	if (value && typeof value === "object") {
		const out: Record<string, unknown> = {};
		for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
			if (key === "data" && typeof item === "string" && item.length > 256) {
				out[key] = "[binary omitted]";
				continue;
			}
			out[key] = shrink(item, depth + 1);
		}
		return out;
	}
	return value;
}

const HERDR_SOURCE = "pi-remote";
const HERDR_AGENT = "pi";

function herdrSocketPath(): string {
	return process.env.HERDR_SOCKET_PATH || path.join(os.homedir(), ".config", "herdr", "herdr.sock");
}

/** One JSON-line request to the herdr socket. Resolves null when unavailable. */
function herdrRequest(method: string, params: unknown, timeoutMs = 2000): Promise<any> {
	return new Promise((resolve) => {
		let settled = false;
		const finish = (value: any) => {
			if (settled) return;
			settled = true;
			try {
				socket.destroy();
			} catch {
				// already gone
			}
			resolve(value);
		};
		const socket = createConnection(herdrSocketPath());
		let buffer = "";
		socket.on("error", () => finish(null));
		socket.on("connect", () => {
			socket.write(`${JSON.stringify({ id: `pi-remote-${Date.now()}`, method, params })}\n`);
		});
		socket.on("data", (chunk) => {
			buffer += chunk.toString();
			const newline = buffer.indexOf("\n");
			if (newline < 0) return;
			try {
				finish(JSON.parse(buffer.slice(0, newline)));
			} catch {
				finish(null);
			}
		});
		const timer = setTimeout(() => finish(null), timeoutMs);
		timer.unref?.();
	});
}

/**
 * Pids from this process up the parent chain. pi is usually started through a
 * sudo wrapper, so its own process group is its own pid while herdr reports the
 * outer wrapper as the pane's foreground group: matching an ancestor is what
 * actually identifies the pane.
 */
function ancestorPids(): number[] {
	const pids: number[] = [];
	let pid = process.pid;
	for (let depth = 0; depth < 8 && pid > 1; depth++) {
		pids.push(pid);
		try {
			const stat = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
			const afterName = stat.slice(stat.lastIndexOf(")") + 2).split(" ");
			pid = Number(afterName[1]); // state ppid ...
		} catch {
			break;
		}
	}
	return pids;
}

export default function (pi: ExtensionAPI) {
	let server: http.Server | undefined;
	let loopbackServer: http.Server | undefined;
	let tickTimer: NodeJS.Timeout | undefined;
	let heartbeat: NodeJS.Timeout | undefined;
	let clients = new Set<http.ServerResponse>();
	let context: ExtensionContext | undefined;
	let seq = 0;
	let notified = false;
	/** The address the running bind actually took, including the loopback fallback. */
	let boundAddress: { host: string; port: number } | undefined;
	let binding = false;
	/**
	 * Bumped whenever this session gives the address up, so a bind that was already
	 * in flight when the user released cannot land and start serving anyway.
	 */
	let serveGeneration = 0;
	let leaseClaimedAt = "";
	let stateWatcher: fs.FSWatcher | undefined;
	let takeoverDebounce: NodeJS.Timeout | undefined;
	let paneId: string | undefined;
	let paneResolved = false;
	let lastBadge = "";
	let lastReportedState = "";
	let questionWatcher: fs.FSWatcher | undefined;
	let questionDebounce: NodeJS.Timeout | undefined;
	let lastQuestionSignature: string | null = null;

	// Nothing here reads the token at load time: a broken config directory must not
	// stop pi from starting, and rotation has to apply without a restart.
	const heartbeatMs = Number(process.env.PI_REMOTE_HEARTBEAT_MS || 10000);
	const staleMs = Number(process.env.PI_REMOTE_STALE_MS || 30000);
	const allowNonTui = process.env.PI_REMOTE_ALLOW_NON_TUI === "1";

	function nowIso(): string {
		return new Date().toISOString();
	}

	function sessionFile(): string | null {
		try {
			return context?.sessionManager?.getSessionFile() ?? null;
		} catch {
			return null;
		}
	}

	function sessionId(): string | null {
		try {
			return context?.sessionManager?.getSessionId() ?? null;
		} catch {
			return null;
		}
	}

	function sessionName(): string | null {
		try {
			return context?.sessionManager?.getSessionName() ?? null;
		} catch {
			return null;
		}
	}

	/** The lease for this session's address. Only a successful bind or a heartbeat writes it. */
	function readLease(): LeaseRecord | undefined {
		try {
			return JSON.parse(fs.readFileSync(leasePath(), "utf8")) as LeaseRecord;
		} catch {
			return undefined;
		}
	}

	function readTakeover(): TakeoverRecord | undefined {
		try {
			return JSON.parse(fs.readFileSync(takeoverPath(), "utf8")) as TakeoverRecord;
		} catch {
			return undefined;
		}
	}

	function leaseOwnerAlive(lease: LeaseRecord): boolean {
		if (lease.pid === process.pid) return true;
		try {
			process.kill(lease.pid, 0);
			return true;
		} catch {
			return false;
		}
	}

	/** A fresh takeover naming another pid. This process never reacts to its own. */
	function takeoverActive(): boolean {
		return takeoverIsValid(readTakeover(), Date.now(), process.pid);
	}

	/**
	 * Rule 1: the lease is written only after a bind succeeds, and it records the
	 * address that bind actually took, not the one that was configured.
	 */
	function writeLease(claim: boolean): void {
		if (!boundAddress) return;
		if (claim || !leaseClaimedAt) leaseClaimedAt = nowIso();
		writeAtomic(leasePath(), {
			pid: process.pid,
			host: configuredHost(),
			port: configuredPort(),
			boundHost: boundAddress.host,
			boundPort: boundAddress.port,
			sessionId: sessionId(),
			sessionName: sessionName(),
			cwd: context?.cwd ?? process.cwd(),
			mode: context?.mode ?? "unknown",
			sessionFile: sessionFile(),
			claimedAt: leaseClaimedAt,
			heartbeatAt: nowIso(),
		} satisfies LeaseRecord);
	}

	/**
	 * Rule 2: refresh only a lease that names this pid, or a missing one while this
	 * process is serving. A lease naming another pid belongs to its owner and is
	 * never touched, so a heartbeat can never revert a handover.
	 */
	function refreshLease(): void {
		if (!boundAddress) return;
		if (!mayRefreshLease(readLease(), process.pid, Boolean(server))) return;
		writeLease(false);
	}

	function writeTakeover(): void {
		writeAtomic(takeoverPath(), { pid: process.pid, at: nowIso(), nonce: crypto.randomUUID() });
	}

	/** Only an interactive session serves, unless the harness opts in. */
	function canServe(): boolean {
		return allowNonTui || context?.mode === "tui";
	}

	function emit(type: string, data: unknown): void {
		if (clients.size === 0) {
			return;
		}
		const frame = `data: ${JSON.stringify({ seq: ++seq, at: nowIso(), type, data: shrink(data) })}\n\n`;
		for (const client of [...clients]) {
			try {
				client.write(frame);
			} catch {
				clients.delete(client);
				updateStatus();
			}
		}
	}

	/**
	 * Footer indicator: says a phone is attached, and only then. The herdr badge
	 * answers "which pane", this answers "is my phone on the other end of it" from
	 * inside the session itself. The marks are loud on purpose: this is one dim row
	 * among many, and it is the only sign that the pane in front of you is the one
	 * the phone is driving.
	 */
	function updateStatus(): void {
		const count = clients.size;
		const text =
			count === 0
				? undefined
				: count === 1
					? "!!!PI-Remote connected!!!"
					: `!!!PI-Remote: ${count} phones connected!!!`;
		try {
			context?.ui?.setStatus?.("pi-remote", text);
		} catch {
			// The footer is cosmetic: never let it fail a request.
		}
	}

	function statePayload() {
		const ctx = context;
		const model = ctx?.model;
		return {
			sessionFile: sessionFile(),
			sessionId: ctx?.sessionManager?.getSessionId() ?? null,
			sessionName: ctx?.sessionManager?.getSessionName() ?? null,
			cwd: ctx?.cwd ?? process.cwd(),
			mode: ctx?.mode ?? null,
			model: model ? { provider: model.provider, id: model.id } : null,
			thinkingLevel: ctx?.thinkingLevel ?? null,
			idle: ctx ? ctx.isIdle() : null,
			pendingMessages: ctx ? ctx.hasPendingMessages() : null,
			contextUsage: ctx?.getContextUsage() ?? null,
			question: readPending(),
			pid: process.pid,
			serving: Boolean(server),
			// The pid that holds the bind. This request can only arrive on the bind
			// holder's socket, so it is this process; the lease is the fallback.
			ownerPid: server ? process.pid : (readLease()?.pid ?? null),
			host: configuredHost(),
			port: configuredPort(),
			boundHost: boundAddress?.host ?? null,
			boundPort: boundAddress?.port ?? null,
			serverTime: nowIso(),
		};
	}

	function historyPayload(limit: number) {
		const sessionManager = context?.sessionManager;
		if (!sessionManager) {
			return { messages: [], sessionFile: null };
		}
		const branch = sessionManager.getBranch();
		const entries = branch.filter((entry) => entry.type === "message").slice(-limit);
		return {
			sessionFile: sessionFile(),
			sessionId: sessionManager.getSessionId() ?? null,
			total: branch.length,
			// Each entry is trimmed, the list itself is not. shrink() keeps the head
			// of an over-long array and marks the rest, which on this list drops the
			// newest entries, the prompt and the answer in progress among them, and
			// leaves a phone showing history that stops in the past. The limit above
			// is what bounds this list.
			messages: entries.map((entry) => shrink(entry)),
		};
	}

	/**
	 * The words a message carries, and nothing else. Thinking and tool calls are
	 * how an answer was reached rather than part of it, and a tool result is
	 * process, so only text blocks count. This is the same rule the app draws by,
	 * which is what keeps a search hit and the thing it names the same text.
	 */
	function textBlocksOf(content: unknown): string {
		if (typeof content === "string") return content;
		if (!Array.isArray(content)) return "";
		const parts: string[] = [];
		for (const block of content) {
			if (block && typeof block === "object" && (block as { type?: unknown }).type === "text") {
				const text = (block as { text?: unknown }).text;
				if (typeof text === "string") parts.push(text);
			}
		}
		return parts.join("\n");
	}

	/**
	 * A window of the text with the match inside it, so a hit shows what it says
	 * rather than where it says it. Whitespace is collapsed first, because a
	 * snippet read on a phone is one line and the match may sit behind a run of
	 * newlines.
	 */
	function snippetAround(text: string, at: number, matchLength: number): string {
		const start = Math.max(0, at - SNIPPET_BEFORE);
		const end = Math.min(text.length, at + matchLength + SNIPPET_AFTER);
		let snippet = text.slice(start, end).replace(/\s+/g, " ").trim();
		if (start > 0) snippet = `…${snippet}`;
		if (end < text.length) snippet = `${snippet}…`;
		return snippet.length > SNIPPET_MAX ? `${snippet.slice(0, SNIPPET_MAX)}…` : snippet;
	}

	/**
	 * Message entries on the branch whose text contains the query, newest first.
	 * A match is only placeable if it names the prompt it answers, so the walk
	 * carries the nearest preceding user message and hands it back with each hit.
	 * An empty or blank query matches nothing rather than everything.
	 */
	function searchPayload(query: string, limit: number) {
		const sessionManager = context?.sessionManager;
		const needle = query.trim().toLowerCase();
		const base = {
			query: query.trim(),
			sessionFile: sessionFile(),
			sessionId: sessionManager?.getSessionId() ?? null,
		};
		if (!sessionManager || !needle) {
			return { ...base, total: 0, results: [] };
		}
		const branch = sessionManager.getBranch();
		const results: Array<{
			entryId: string | null;
			role: string;
			snippet: string;
			prompt: string | null;
			turnId: string | null;
		}> = [];
		let promptText: string | null = null;
		let promptId: string | null = null;
		for (const entry of branch) {
			if (entry.type !== "message") continue;
			const message = entry.message;
			const role = typeof message.role === "string" ? message.role : "";
			// Only what the app draws takes part: a prompt, or an answer. A tool
			// result is process, and is left out even when it carries text.
			if (role !== "user" && role !== "assistant") continue;
			const text = textBlocksOf((message as { content?: unknown }).content);
			if (role === "user") {
				// A user message is the prompt of its own turn, and of everything
				// after it until the next one.
				promptId = entry.id;
				promptText = text;
			}
			if (!text) continue;
			const at = text.toLowerCase().indexOf(needle);
			if (at < 0) continue;
			results.push({
				entryId: entry.id,
				role,
				snippet: snippetAround(text, at, needle.length),
				prompt: promptText,
				turnId: promptId,
			});
		}
		// The branch reads oldest first; a search reads best from the newest.
		results.reverse();
		return { ...base, total: results.length, results: results.slice(0, limit) };
	}

	/**
	 * The things a prompt may dispatch: extension commands, prompt templates and
	 * skills, the same set the laptop completes on. getCommands() reports exactly
	 * those three sources and nothing else. Built-in slash commands are left out
	 * on purpose: they are not in this list, and one that only exists in the
	 * interactive TUI is a keystroke the TUI handles rather than text a prompt
	 * expands, so sending it from here would be a name that quietly does nothing.
	 * Offering it would promise a command the phone cannot run.
	 *
	 * sourceInfo is dropped: it is a local path or a plugin name the phone has no
	 * use for, and the app only draws the source kind.
	 */
	function commandsPayload() {
		const base = { sessionId: sessionId() };
		if (!context) {
			return { ...base, commands: [] };
		}
		try {
			const commands = pi.getCommands().map((command) => ({
				name: command.name,
				description: command.description ?? "",
				source: command.source,
			}));
			return { ...base, commands };
		} catch {
			// A session caught between states answers with none rather than a 500;
			// the phone does not need a command list to use the bridge.
			return { ...base, commands: [] };
		}
	}

	/**
	 * Pairing trades a short-lived, single-use code for a device token, so the token
	 * itself never lands in a QR image, a deep link, or the phone's camera history.
	 * Reading the code off the laptop screen is the one step a human must do.
	 */
	let pendingPair: { code: string; createdAt: number; label: string } | undefined;
	const pairAttempts = new Map<string, { count: number; blockedUntil: number }>();

	/** Constant-time compare, so a wrong code leaks nothing by timing. */
	function sameString(a: string, b: string): boolean {
		const left = Buffer.from(a, "utf8");
		const right = Buffer.from(b, "utf8");
		return left.length === right.length && crypto.timingSafeEqual(left, right);
	}

	function mintPairCode(label: string): { code: string; expiresAt: string } {
		const code = crypto.randomBytes(16).toString("base64url");
		pendingPair = { code, createdAt: Date.now(), label };
		pairAttempts.clear();
		audit({ event: "pair_code_issued", label, ttlMs: PAIR_TTL_MS });
		return { code, expiresAt: new Date(Date.now() + PAIR_TTL_MS).toISOString() };
	}

	/**
	 * Host the pairing QR should name. Prefer the MagicDNS name: Android decides
	 * cleartext-HTTP permission per hostname before connecting, so a raw 100.x address
	 * is what the app now refuses by default. Falls back to the bound host with a
	 * warning when Tailscale cannot be asked.
	 */
	function pairHost(): { host: string; source: string } {
		const configured = process.env.PI_REMOTE_PAIR_HOST;
		if (configured) return { host: configured, source: "PI_REMOTE_PAIR_HOST" };
		const status = spawnSync("tailscale", ["status", "--json"], {
			encoding: "utf8",
			timeout: 5000,
		});
		if (!status.error && status.status === 0 && status.stdout) {
			try {
				const name = JSON.parse(status.stdout)?.Self?.DNSName;
				if (typeof name === "string" && name.length > 0) {
					return { host: name.replace(/\.$/, ""), source: "tailscale status" };
				}
			} catch {
				// fall through to the bound host
			}
		}
		return { host: process.env.PI_REMOTE_HOST || DEFAULT_HOST, source: "fallback" };
	}

	/** PATH lookup without shelling out; returns "" when the command is absent. */
	function whichSync(cmd: string): string {
		for (const dir of (process.env.PATH || "").split(":")) {
			if (!dir) continue;
			const candidate = `${dir}/${cmd}`;
			try {
				if (fs.statSync(candidate).isFile()) return candidate;
			} catch {
				// keep looking
			}
		}
		return "";
	}

	/**
	 * Open an image in a viewer. xdg-open is the portable choice, but on this
	 * desktop it resolves to a Chromium-based handler that refuses to run as root
	 * (pi runs as root), so prefer viewers that do work under root.
	 */
	function openImage(file: string): { ok: boolean; viewer: string } {
		for (const viewer of ["eog", "gwenview", "feh", "xdg-open"]) {
			if (!whichSync(viewer)) continue;
			try {
				spawn(viewer, [file], { detached: true, stdio: "ignore" }).unref();
				return { ok: true, viewer };
			} catch {
				// try the next viewer
			}
		}
		return { ok: false, viewer: "" };
	}

	type PortHolder = { pid: number; comm: string };

	/**
	 * Which other processes listen on the bridge port, with their command names, so
	 * a blocked address can be reported as "pid N (comm)" and told apart from a pi
	 * session that holds the lease. Switched to bind by addr:port and matching any
	 * process, this is what makes S4 nameable at all.
	 */
	function portHolders(port: number): PortHolder[] {
		const probe = spawnSync("ss", ["-ltnp", `sport = :${port}`], { encoding: "utf8" });
		const text = `${probe.stdout || ""}${probe.stderr || ""}`;
		const holders = new Map<number, PortHolder>();
		for (const match of text.matchAll(/"([^"]+)",pid=(\d+)/g)) {
			const pid = Number(match[2]);
			if (!pid || pid === process.pid || holders.has(pid)) continue;
			holders.set(pid, { pid, comm: match[1] });
		}
		return [...holders.values()];
	}

	function notePairFailure(key: string, reason: string, req: http.IncomingMessage): void {
		const entry = pairAttempts.get(key) ?? { count: 0, blockedUntil: 0 };
		entry.count += 1;
		if (entry.count >= PAIR_MAX_ATTEMPTS) {
			entry.blockedUntil = Date.now() + PAIR_LOCKOUT_MS;
			entry.count = 0;
			audit({ event: "pair_blocked", ip: key, client: clientLabel(req) });
		}
		pairAttempts.set(key, entry);
		audit({ event: "pair_failed", reason, ip: key, client: clientLabel(req) });
	}

	/**
	 * Unauthenticated on purpose: the caller is a phone that has no token yet, and the
	 * one-time code is its credential. Everything else here still needs a device token.
	 */
	function handlePair(req: http.IncomingMessage, res: http.ServerResponse): void {
		void (async () => {
			const key = clientKey(req);
			if ((pairAttempts.get(key)?.blockedUntil ?? 0) > Date.now()) {
				sendJson(res, 429, { error: "too many attempts, try again shortly" });
				return;
			}
			try {
				const body = await readBody(req);
				const code = typeof body.code === "string" ? body.code.trim() : "";
				const label = ((typeof body.label === "string" && body.label.trim()) || "phone").slice(0, 40);
				if (!pendingPair) {
					notePairFailure(key, "no code pending", req);
					sendJson(res, 409, { error: "no pairing is waiting; run /pair on the laptop" });
					return;
				}
				if (Date.now() - pendingPair.createdAt > PAIR_TTL_MS) {
					pendingPair = undefined;
					notePairFailure(key, "expired", req);
					sendJson(res, 410, { error: "the pairing code expired; run /pair again" });
					return;
				}
				if (!code || !sameString(code, pendingPair.code)) {
					notePairFailure(key, "wrong code", req);
					sendJson(res, 403, { error: "wrong pairing code" });
					return;
				}
				pendingPair = undefined; // single use, spent before the response is written
				const token = newToken();
				writeTokens([
					...loadTokens().filter((existing) => existing.label !== label),
					{ label, token, created: nowIso() },
				]);
				tokenCache = { at: 0, entries: [] };
				pairAttempts.delete(key);
				audit({ event: "pair_succeeded", label, ip: key, client: clientLabel(req) });
				context?.ui?.setWidget?.(PAIR_WIDGET_ID, ["pi-remote: paired ✓"]);
				setTimeout(() => context?.ui?.setWidget?.(PAIR_WIDGET_ID, undefined), 4000).unref?.();
				sendJson(res, 200, { ok: true, device: label, token });
			} catch (error) {
				sendJson(res, 500, { error: String((error as Error)?.message || error) });
			}
		})();
	}

	const authFailures = new Map<string, { count: number; blockedUntil: number }>();

	function clientKey(req: http.IncomingMessage): string {
		return req.socket.remoteAddress || "unknown";
	}

	function blockedUntilFor(req: http.IncomingMessage): number {
		return authFailures.get(clientKey(req))?.blockedUntil ?? 0;
	}

	function noteAuthFailure(req: http.IncomingMessage): void {
		const key = clientKey(req);
		const entry = authFailures.get(key) ?? { count: 0, blockedUntil: 0 };
		entry.count += 1;
		if (entry.count >= 5) {
			entry.blockedUntil = Date.now() + 60000;
			entry.count = 0;
			audit({ event: "auth_blocked", ip: key });
		}
		authFailures.set(key, entry);
	}

	/** Returns the device label when the request is authorised. */
	/** App build string, when the client sends one. */
	function clientLabel(req: http.IncomingMessage): string | null {
		const value = req.headers["x-pi-client"];
		return typeof value === "string" ? value.slice(0, 40) : null;
	}

	function authorized(req: http.IncomingMessage): string | undefined {
		const header = req.headers.authorization || "";
		const prefix = "Bearer ";
		if (!header.startsWith(prefix)) {
			noteAuthFailure(req);
			audit({ event: "auth_missing", ip: clientKey(req), client: clientLabel(req) });
			return undefined;
		}
		const entry = matchToken(header.slice(prefix.length).trim());
		if (!entry) {
			noteAuthFailure(req);
			audit({ event: "auth_failed", ip: clientKey(req), client: clientLabel(req) });
			return undefined;
		}
		authFailures.delete(clientKey(req));
		return entry.label;
	}

	function sendJson(res: http.ServerResponse, status: number, body: unknown): void {
		const payload = JSON.stringify(body);
		res.writeHead(status, {
			"content-type": "application/json; charset=utf-8",
			"content-length": Buffer.byteLength(payload),
			"cache-control": "no-store",
		});
		res.end(payload);
	}

	async function readBody(req: http.IncomingMessage): Promise<Record<string, unknown>> {
		const chunks: Buffer[] = [];
		let size = 0;
		for await (const chunk of req) {
			size += (chunk as Buffer).length;
			if (size > 262144) {
				throw new Error("request body too large");
			}
			chunks.push(chunk as Buffer);
		}
		if (chunks.length === 0) {
			return {};
		}
		return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
	}

	/**
	 * Rule 10: a mutating call that names another session is refused with 409 and
	 * changes nothing. Absent means an older app, and is accepted, so the bridge can
	 * ship before the phones send the field.
	 */
	function wrongSession(body: Record<string, unknown>): boolean {
		const requested = typeof body.sessionId === "string" ? body.sessionId.trim() : "";
		if (!requested) return false;
		return requested !== sessionId();
	}

	/** Second, loopback-only listener so `tailscale serve` can front the bridge. */
	function startLoopbackServer(port: number): void {
		if (loopbackServer || process.env.PI_REMOTE_NO_LOOPBACK === "1") {
			return;
		}
		const candidate = http.createServer(handleRequest);
		candidate.on("error", () => {
			try {
				candidate.close();
			} catch {
				// ignore
			}
			loopbackServer = undefined;
		});
		candidate.listen(port, "127.0.0.1", () => {
			loopbackServer = candidate;
		});
	}

	function handleRequest(req: http.IncomingMessage, res: http.ServerResponse): void {
		const url = new URL(req.url || "/", "http://localhost");

		if (blockedUntilFor(req) > Date.now()) {
			sendJson(res, 429, { error: "too many failed attempts, try again shortly" });
			return;
		}

		// The only unauthenticated endpoint: a phone with no token yet spends a code here.
		if (req.method === "POST" && url.pathname === "/api/pair") {
			handlePair(req, res);
			return;
		}

		const device = authorized(req);
		if (!device) {
			sendJson(res, 401, { error: "unauthorized" });
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/health") {
			sendJson(res, 200, { ok: true, serverTime: nowIso() });
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/state") {
			sendJson(res, 200, statePayload());
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/history") {
			const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 40), 1), 500);
			sendJson(res, 200, historyPayload(limit));
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/search") {
			const query = url.searchParams.get("q") || "";
			// The same clamp as /api/history; shrink() then caps the list itself at 40.
			const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 40), 1), 500);
			sendJson(res, 200, shrink(searchPayload(query, limit)));
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/commands") {
			sendJson(res, 200, shrink(commandsPayload()));
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/events") {
			res.writeHead(200, {
				"content-type": "text/event-stream; charset=utf-8",
				"cache-control": "no-store",
				connection: "keep-alive",
			});
			res.write(`retry: 3000\n\n`);
			clients.add(res);
			void reportPresence();
			updateStatus();
			res.write(
				`data: ${JSON.stringify({ seq: ++seq, at: nowIso(), type: "state", data: shrink(statePayload()) })}\n\n`,
			);
			res.write(
				`data: ${JSON.stringify({ seq: ++seq, at: nowIso(), type: "question", data: { pending: readPending() } })}\n\n`,
			);
			req.on("close", () => {
				clients.delete(res);
				void reportPresence();
				updateStatus();
			});
			return;
		}

		if (req.method === "POST" && url.pathname === "/api/prompt") {
			void (async () => {
				try {
					const body = await readBody(req);
					if (wrongSession(body)) {
						sendJson(res, 409, { error: "this bridge serves a different session", sessionId: sessionId() });
						return;
					}
					const text = typeof body.text === "string" ? body.text : "";
					if (!text.trim()) {
						sendJson(res, 400, { error: "text is required" });
						return;
					}
					const ctx = context;
					if (!ctx) {
						sendJson(res, 503, { error: "session not ready" });
						return;
					}
					const busy = !ctx.isIdle();
					const requested = typeof body.deliverAs === "string" ? body.deliverAs : "steer";
					const deliverAs = requested === "followUp" ? "followUp" : "steer";
					// expandPromptTemplates is what makes a phone prompt behave like a
					// prompt typed on the laptop: /name dispatches an extension command
					// or a skill, and a template name expands. Without it the text stays
					// literal and /skill:foo does nothing. There is no flag for the app
					// to send, because the point is parity, not a second way to prompt.
					if (busy) {
						pi.sendUserMessage(text, { deliverAs, expandPromptTemplates: true });
					} else {
						pi.sendUserMessage(text, { expandPromptTemplates: true });
					}
					emit("prompt_accepted", { busy, deliverAs: busy ? deliverAs : "immediate", chars: text.length });
					audit({
						event: "prompt",
						device,
						client: clientLabel(req),
						chars: text.length,
						digest: crypto.createHash("sha256").update(text).digest("hex").slice(0, 16),
						deliverAs: busy ? deliverAs : "immediate",
					});
					sendJson(res, 200, { ok: true, busy, deliverAs: busy ? deliverAs : "immediate" });
				} catch (error) {
					sendJson(res, 500, { error: String((error as Error)?.message || error) });
				}
			})();
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/release/latest.json") {
			const manifest = readReleaseManifest();
			if (!manifest) {
				sendJson(res, 404, { error: "no release synced on this laptop" });
				return;
			}
			sendJson(res, 200, manifest);
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/release/apk") {
			const manifest = readReleaseManifest();
			const fileName = typeof manifest?.fileName === "string" ? path.basename(manifest.fileName) : "";
			if (!fileName) {
				sendJson(res, 404, { error: "no release synced on this laptop" });
				return;
			}
			const apk = path.join(releaseDir(), fileName);
			let size: number;
			try {
				size = fs.statSync(apk).size;
			} catch {
				sendJson(res, 404, { error: `apk ${fileName} is missing` });
				return;
			}
			audit({ event: "apk_download", device, file: fileName, bytes: size });
			res.writeHead(200, {
				"content-type": "application/vnd.android.package-archive",
				"content-length": size,
				"cache-control": "no-store",
			});
			fs.createReadStream(apk).pipe(res);
			return;
		}

		if (req.method === "GET" && url.pathname === "/api/question") {
			sendJson(res, 200, { pending: readPending() });
			return;
		}

		if (req.method === "POST" && url.pathname === "/api/answer") {
			void (async () => {
				try {
					const body = await readBody(req);
					if (wrongSession(body)) {
						sendJson(res, 409, { error: "this bridge serves a different session", sessionId: sessionId() });
						return;
					}
					const pending = readPending();
					if (!pending) {
						sendJson(res, 409, { error: "no question is waiting for an answer" });
						return;
					}
					const accepted = writeAnswer({
						questionId: typeof body.questionId === "string" ? body.questionId : pending.questions[0]?.id,
						value: typeof body.value === "string" ? body.value : "",
						custom: body.custom === true,
						cancel: body.cancel === true,
						submit: body.submit === true,
					});
					if (!accepted) {
						sendJson(res, 409, { error: "question disappeared before the answer arrived" });
						return;
					}
					emit("question_answered", { questionId: body.questionId ?? null, value: body.value ?? null });
					sendJson(res, 200, { ok: true });
				} catch (error) {
					sendJson(res, 500, { error: String((error as Error)?.message || error) });
				}
			})();
			return;
		}

		if (req.method === "POST" && url.pathname === "/api/abort") {
			void (async () => {
				try {
					// Abort takes an optional sessionId but no other payload; an empty or
					// unparsable body means "no session named", not a failure.
					let body: Record<string, unknown> = {};
					try {
						body = await readBody(req);
					} catch {
						body = {};
					}
					if (wrongSession(body)) {
						sendJson(res, 409, { error: "this bridge serves a different session", sessionId: sessionId() });
						return;
					}
					context?.abort();
					audit({ event: "abort", device });
					emit("aborted", {});
					sendJson(res, 200, { ok: true });
				} catch (error) {
					sendJson(res, 500, { error: String((error as Error)?.message || error) });
				}
			})();
			return;
		}

		sendJson(res, 404, { error: "not found" });
	}

	function stopHeartbeat(): void {
		if (heartbeat) {
			clearInterval(heartbeat);
			heartbeat = undefined;
		}
	}

	/**
	 * Close everything that holds the address. The lease is deliberately left alone:
	 * after a takeover the new owner overwrites it once its own bind succeeds, and on
	 * release or shutdown the caller removes its own lease explicitly.
	 */
	function closeServer(): void {
		stopHeartbeat();
		for (const client of [...clients]) {
			try {
				client.end();
			} catch {
				// already gone
			}
		}
		clients = new Set();
		updateStatus();
		if (server) {
			const current = server;
			server = undefined;
			try {
				current.close();
			} catch {
				// ignore
			}
		}
		if (loopbackServer) {
			const current = loopbackServer;
			loopbackServer = undefined;
			try {
				current.close();
			} catch {
				// ignore
			}
		}
		boundAddress = undefined;
	}

	/** What a successful bind sets up: the lease, the heartbeat and the loopback twin. */
	function onBound(host: string, port: number): void {
		boundAddress = { host, port };
		writeLease(true);
		stopHeartbeat();
		heartbeat = setInterval(() => {
			// Rule 2 lives in refreshLease: a lease that is not ours is never touched.
			refreshLease();
			for (const client of [...clients]) {
				try {
					client.write(`: ping\n\n`);
				} catch {
					clients.delete(client);
					updateStatus();
				}
			}
		}, heartbeatMs);
		heartbeat.unref?.();
		if (host !== FALLBACK_HOST) {
			startLoopbackServer(port);
		}
		if (!notified && context?.hasUI) {
			notified = true;
			context.ui.notify?.(`pi-remote listening on ${host}:${port}`, "info");
		}
		// The watcher is the fast path, but it can fire while this bind is still in
		// flight and then find no server to close. Re-check here, so a bind that lost
		// that race does not serve under a fresh takeover (rule 3).
		if (takeoverActive()) {
			closeServer();
		}
	}

	/** One listen attempt. Resolves when the bind succeeds or fails; it never throws. */
	function listenOnce(host: string, port: number): Promise<{ ok: true } | { ok: false; code: string }> {
		return new Promise((resolve) => {
			if (server) {
				resolve({ ok: true });
				return;
			}
			const generation = serveGeneration;
			const candidate = http.createServer(handleRequest);
			let settled = false;
			const finish = (result: { ok: true } | { ok: false; code: string }) => {
				if (settled) return;
				settled = true;
				resolve(result);
			};
			candidate.on("error", (error: NodeJS.ErrnoException) => {
				try {
					candidate.close();
				} catch {
					// ignore
				}
				// After a successful bind this fires on accept errors (EMFILE, ECONNABORTED).
				// The socket is gone, so nothing may keep claiming to serve: a session that
				// keeps its lease alive over a dead socket never rebinds.
				if (server === candidate) {
					server = undefined;
					boundAddress = undefined;
					stopHeartbeat();
					context?.ui?.notify?.(`pi-remote: bridge socket failed (${error.code || "error"})`, "warning");
				}
				finish({ ok: false, code: error.code || "error" });
			});
			candidate.listen(port, host, () => {
				if (generation !== serveGeneration) {
					// Released while this bind was in flight. Hand the address straight back.
					try {
						candidate.close();
					} catch {
						// ignore
					}
					finish({ ok: false, code: "released" });
					return;
				}
				// Rule 1: the bind decided. Only a bind that landed may write a lease.
				server = candidate;
				onBound(host, port);
				finish({ ok: true });
			});
		});
	}

	/**
	 * Bind the configured address, falling back to loopback when the host itself is
	 * unavailable. A fallback bind still serves, but every report uses the address
	 * it actually took.
	 */
	async function bindAddress(host: string, port: number): Promise<{ ok: boolean; code?: string }> {
		if (server) return { ok: true };
		const first = await listenOnce(host, port);
		if (first.ok) return { ok: true };
		if (first.code === "EADDRNOTAVAIL" && host !== FALLBACK_HOST) {
			context?.ui?.notify?.(`pi-remote: ${host} unavailable, binding ${FALLBACK_HOST}`, "warning");
			return await listenOnce(FALLBACK_HOST, port);
		}
		return { ok: false, code: first.code };
	}

	/** One bind for the periodic tick; a held address is retried on the next one. */
	async function attemptBind(host: string, port: number): Promise<void> {
		if (server || binding || !canServe()) return;
		binding = true;
		try {
			await bindAddress(host, port);
		} finally {
			binding = false;
		}
	}

	/** Rule 5: write the takeover record, then retry the bind every 50 ms for the window. */
	async function takeoverAndBind(
		host: string,
		port: number,
		timeoutMs: number,
	): Promise<{ ok: boolean; code?: string }> {
		if (server) return { ok: true };
		binding = true;
		try {
			writeTakeover();
			const deadline = Date.now() + timeoutMs;
			let last: { ok: boolean; code?: string } = { ok: false, code: "unknown" };
			do {
				last = await bindAddress(host, port);
				if (last.ok) return last;
				// EADDRNOTAVAIL already fell back to loopback inside bindAddress; only a
				// held address is worth waiting on.
				if (last.code !== "EADDRINUSE") return last;
				await sleep(50);
			} while (Date.now() < deadline);
			return last;
		} finally {
			binding = false;
		}
	}

	function sleep(ms: number): Promise<void> {
		return new Promise((resolve) => setTimeout(resolve, ms));
	}

	/**
	 * Crash recovery and the heartbeat. Binding a free address is all that is left to
	 * this loop. The reaction to a takeover is the watcher; the check below is the
	 * backstop for a coalesced watch event or one that arrived mid-bind.
	 */
	function tick(): void {
		if (process.env.PI_REMOTE_DISABLE === "1") {
			return;
		}
		if (!server && tokens().length === 0) {
			// A bridge nobody can authenticate to is not worth binding; /pair and
			// /pi-remote bind explicitly and then mint the first token.
			return;
		}
		if (server) {
			const decision = decideState({
				disabled: false,
				serving: true,
				binding,
				selfPid: process.pid,
				lease: readLease(),
				ownerAlive: false,
				takeoverActive: takeoverActive(),
				portHeldByForeign: false,
				now: Date.now(),
				staleMs,
			});
			if (decision.state !== "serving") {
				reactToTakeover();
				return;
			}
			refreshLease();
			void reportPresence();
			return;
		}
		if (!canServe()) {
			return;
		}
		const lease = readLease();
		const decision = decideState({
			disabled: false,
			serving: false,
			binding,
			selfPid: process.pid,
			lease,
			ownerAlive: lease ? leaseOwnerAlive(lease) : false,
			takeoverActive: takeoverActive(),
			// The tick never probes the port; the command paths do, and report it.
			portHeldByForeign: false,
			now: Date.now(),
			staleMs,
		});
		if (decision.shouldBind) {
			void attemptBind(configuredHost(), configuredPort());
		}
	}

	/** Publish question widget changes to connected clients. */
	function watchQuestions(): void {
		if (questionWatcher) {
			return;
		}
		try {
			fs.mkdirSync(remoteDir(), { recursive: true, mode: 0o700 });
			questionWatcher = fs.watch(remoteDir(), () => {
				if (questionDebounce) {
					clearTimeout(questionDebounce);
				}
				questionDebounce = setTimeout(() => {
					const pending = readPending();
					// Only forward real changes: the directory also sees answer
					// files and heartbeats, which would otherwise spam clients.
					const signature = pending ? `${pending.id}:${pending.updatedAt}` : "none";
					if (signature === lastQuestionSignature) {
						return;
					}
					lastQuestionSignature = signature;
					emit("question", { pending });
				}, 150);
				questionDebounce.unref?.();
			});
			questionWatcher.on("error", () => {
				questionWatcher = undefined;
			});
		} catch {
			// Watching is best effort: the app can still poll /api/question.
		}
	}

	/**
	 * Rule 4: the reaction to a takeover record is a watch on the state directory,
	 * not the tick. The watch is filtered to the one filename that addresses this
	 * host and port, then debounced. The same directory holds tokens.json (and on
	 * this host other records), so reacting to every event would let a lease
	 * heartbeat or a token rotation retrigger this handler. This process writes the
	 * takeover file only when it is not serving, and checks that guard again in
	 * reactToTakeover, so it can never make itself drop.
	 */
	function watchTakeover(): void {
		if (stateWatcher) {
			return;
		}
		try {
			fs.mkdirSync(stateDir(), { recursive: true, mode: 0o700 });
			const host = configuredHost();
			const port = configuredPort();
			stateWatcher = fs.watch(stateDir(), (_eventType, filename) => {
				const name = typeof filename === "string" ? filename : filename ? String(filename) : "";
				// Only the exact takeover filename for this address. The lease, its .tmp
				// sibling and tokens.json live here too. The audit log and the question files
				// are in remoteDir(), which has its own watcher.
				if (!isTakeoverFile(name, host, port)) return;
				if (takeoverDebounce) {
					clearTimeout(takeoverDebounce);
				}
				takeoverDebounce = setTimeout(() => {
					takeoverDebounce = undefined;
					reactToTakeover();
				}, 100);
				takeoverDebounce.unref?.();
			});
			stateWatcher.on("error", () => {
				stateWatcher = undefined;
			});
		} catch {
			// best effort: the tick still refuses to rebind while a takeover is fresh
		}
	}

	/** Rule 3: drop the server now and return to waiting, leaving the lease alone. */
	function reactToTakeover(): void {
		if (!server) {
			return;
		}
		if (!takeoverIsValid(readTakeover(), Date.now(), process.pid)) {
			return;
		}
		closeServer();
		void reportPresence();
	}

	/**
	 * Marks the herdr pane this session runs in. The badge only exists while this
	 * session serves the bridge, which is exactly the question it answers: which
	 * pane is the phone connected to.
	 */
	async function reportPresence(): Promise<void> {
		if (!paneId && !paneResolved) {
			paneResolved = true;
			const ancestors = new Set(ancestorPids());
			const list = await herdrRequest("pane.list", {});
			const panes: any[] = list?.result?.panes ?? [];
			for (const pane of panes) {
				const info = await herdrRequest("pane.process_info", { pane_id: pane.pane_id });
				const proc = info?.result?.process_info;
				if (!proc) continue;
				const candidates = new Set<number>();
				if (proc.foreground_process_group_id) candidates.add(proc.foreground_process_group_id);
				if (proc.shell_pid) candidates.add(proc.shell_pid);
				for (const entry of proc.foreground_processes ?? []) {
					if (entry?.pid) candidates.add(entry.pid);
				}
				if ([...candidates].some((pid) => ancestors.has(pid))) {
					paneId = pane.pane_id;
					break;
				}
			}
		}
		if (!paneId) {
			return;
		}
		if (!server) {
			if (lastBadge !== "released") {
				lastBadge = "released";
				await herdrRequest("pane.release_agent", {
					pane_id: paneId,
					source: HERDR_SOURCE,
					agent: HERDR_AGENT,
				});
			}
			return;
		}
		const badge = clients.size > 0 ? "remote ●" : "remote ○";
		const state = context?.isIdle() === false ? "working" : "idle";
		if (badge === lastBadge && state === lastReportedState) {
			return;
		}
		lastBadge = badge;
		lastReportedState = state;
		await herdrRequest("pane.report_agent", {
			pane_id: paneId,
			source: HERDR_SOURCE,
			agent: HERDR_AGENT,
			state,
			custom_status: badge,
		});
	}

	function startTicking(): void {
		if (tickTimer) {
			return;
		}
		tickTimer = setInterval(tick, heartbeatMs);
		tickTimer.unref?.();
		tick();
	}

	function releasePaneBadge(): void {
		if (paneId) {
			void herdrRequest("pane.release_agent", {
				pane_id: paneId,
				source: HERDR_SOURCE,
				agent: HERDR_AGENT,
			});
		}
	}

	function stopTicking(): void {
		if (questionWatcher) {
			questionWatcher.close();
			questionWatcher = undefined;
		}
		if (stateWatcher) {
			stateWatcher.close();
			stateWatcher = undefined;
		}
		if (questionDebounce) {
			clearTimeout(questionDebounce);
			questionDebounce = undefined;
		}
		if (takeoverDebounce) {
			clearTimeout(takeoverDebounce);
			takeoverDebounce = undefined;
		}
		if (tickTimer) {
			clearInterval(tickTimer);
			tickTimer = undefined;
		}
		closeServer();
	}

	pi.on("session_start", async (_event, ctx) => {
		context = ctx;
		watchQuestions();
		watchTakeover();
		startTicking();
		// A new session clears every extension status, so the footer has to be
		// re-armed here or a phone that is still attached goes unmentioned.
		updateStatus();
	});

	pi.on("session_shutdown", async () => {
		stopTicking();
		releasePaneBadge();
		// Only ever remove this process's own lease; another owner's is not ours.
		if (readLease()?.pid === process.pid) {
			try {
				fs.rmSync(leasePath(), { force: true });
			} catch {
				// ignore
			}
		}
	});

	// Keep the latest context for HTTP handlers, and mirror activity to clients.
	pi.on("session_info_changed", async (event, ctx) => {
		context = ctx;
		emit("session_info_changed", { name: event.name ?? null });
	});
	pi.on("agent_start", async (_event, ctx) => {
		context = ctx;
		emit("agent_start", {});
		void reportPresence();
	});
	pi.on("agent_end", async (event, ctx) => {
		context = ctx;
		emit("agent_end", { messageCount: event.messages?.length ?? 0 });
	});
	pi.on("agent_settled", async (_event, ctx) => {
		context = ctx;
		emit("agent_settled", {});
		void reportPresence();
	});
	pi.on("turn_start", async (event, ctx) => {
		context = ctx;
		emit("turn_start", { turnIndex: event.turnIndex });
	});
	pi.on("turn_end", async (event, ctx) => {
		context = ctx;
		emit("turn_end", { turnIndex: event.turnIndex });
	});
	pi.on("message_start", async (event, ctx) => {
		context = ctx;
		emit("message_start", { message: event.message });
	});
	pi.on("message_update", async (event, ctx) => {
		context = ctx;
		// The app grows its answer from the delta alone and takes the full text from
		// message_start and message_end, so the message itself is left out here.
		// Sending it on every update made each frame as large as the answer so far,
		// and the phone fell behind the run while it drained.
		emit("message_update", { assistantMessageEvent: event.assistantMessageEvent });
	});
	pi.on("message_end", async (event, ctx) => {
		context = ctx;
		emit("message_end", { message: event.message });
	});
	pi.on("tool_execution_start", async (event, ctx) => {
		context = ctx;
		emit("tool_execution_start", { toolCallId: event.toolCallId, toolName: event.toolName, args: event.args });
	});
	pi.on("tool_execution_end", async (event, ctx) => {
		context = ctx;
		emit("tool_execution_end", {
			toolCallId: event.toolCallId,
			toolName: event.toolName,
			isError: event.isError,
			result: event.result,
		});
	});
	pi.on("model_select", async (event, ctx) => {
		context = ctx;
		emit("model_select", { model: (event as { model?: unknown }).model ?? null });
	});

	/** The holder of the port that is neither this process nor the pi session named by the lease. */
	function holderFor(port: number, lease?: LeaseRecord): PortHolder | undefined {
		return portHolders(port).find((entry) => entry.pid !== lease?.pid);
	}

	pi.registerCommand("remote", {
		description: "pi-remote bridge status (release|rotate|revoke|tokens)",
		handler: async (args, ctx) => {
			const raw = args.trim();
			const [verb = "", ...rest] = raw.split(/\s+/);
			const action = verb.toLowerCase();
			const host = configuredHost();
			const port = configuredPort();

			if (action === "release") {
				// Rule 9: stop the tick, remove this process's own lease and close the
				// server. The timer is not restarted; the session is `stopped` until
				// /pi-remote asks for the address again.
				const address = boundAddress;
				if (tickTimer) {
					clearInterval(tickTimer);
					tickTimer = undefined;
				}
				// A bind already in flight must not land after this and start serving.
				serveGeneration += 1;
				closeServer();
				if (readLease()?.pid === process.pid) {
					try {
						fs.rmSync(leasePath(), { force: true });
					} catch {
						// ignore
					}
				}
				void reportPresence();
				ctx.ui.notify(
					`released ${address ? `${address.host}:${address.port}` : `${host}:${port}`}, not serving`,
					"info",
				);
				return;
			}
			if (action === "rotate") {
				const label = rest.join(" ").trim() || "phone";
				const entries = loadTokens().filter((entry) => entry.label !== label);
				const entry = { label, token: newToken(), created: nowIso() };
				writeTokens([...entries, entry]);
				tokenCache = { at: 0, entries: [] };
				audit({ event: "token_rotated", label });
				ctx.ui.notify(`new token for "${label}": ${entry.token}`, "info");
				return;
			}
			if (action === "revoke") {
				const label = rest.join(" ").trim();
				const entries = loadTokens();
				const kept = entries.filter((entry) => entry.label !== label);
				if (!label || kept.length === entries.length) {
					const devices = entries.map((entry) => `"${entry.label}"`).join(", ");
					ctx.ui.notify(`no device labelled "${label}"; devices: ${devices || "none"}`, "warning");
					return;
				}
				writeTokens(kept);
				tokenCache = { at: 0, entries: [] };
				audit({ event: "token_revoked", label });
				ctx.ui.notify(
					`revoked "${label}", ${kept.length} device token${kept.length === 1 ? "" : "s"} left`,
					"warning",
				);
				return;
			}
			if (action === "tokens") {
				const list = loadTokens().map((entry) => `${entry.label} (${entry.token.slice(0, 8)}…)`).join(", ");
				ctx.ui.notify(`devices: ${list || "none"}`, list ? "info" : "warning");
				return;
			}

			// Status. Serving reports the bound address; otherwise the lease and the
			// port holder tell "another pi session" from "a foreign process".
			const lease = readLease();
			const holder = holderFor(port, lease);
			const decision = decideState({
				disabled: process.env.PI_REMOTE_DISABLE === "1",
				serving: Boolean(server),
				binding,
				selfPid: process.pid,
				lease,
				ownerAlive: lease ? leaseOwnerAlive(lease) : false,
				takeoverActive: takeoverActive(),
				portHeldByForeign: Boolean(holder),
				now: Date.now(),
				staleMs,
			});
			if (decision.state === "serving" && boundAddress) {
				ctx.ui.notify(
					`serving ${boundAddress.host}:${boundAddress.port} as ${sessionName() ?? sessionId() ?? "unnamed"} · ${loadTokens().length} device tokens · audit ${auditPath()}`,
					"info",
				);
				return;
			}
			if (decision.state === "waiting" && !decision.shouldBind && lease) {
				ctx.ui.notify(
					`idle · another pi session holds ${host}:${port} (pid ${lease.pid}, ${lease.cwd ?? "?"})`,
					"warning",
				);
				return;
			}
			if (holder) {
				ctx.ui.notify(
					`idle · ${host}:${port} is held by pid ${holder.pid} (${holder.comm}), not a pi session`,
					"warning",
				);
				return;
			}
			ctx.ui.notify(`idle · not serving ${host}:${port}`, "warning");
		},
	});

	pi.registerCommand("pi-remote", {
		description: "take the bridge over: this session serves the phone",
		handler: async (_args, ctx) => {
			const host = configuredHost();
			const port = configuredPort();
			if (process.env.PI_REMOTE_DISABLE === "1") {
				ctx.ui.notify("bridge disabled by PI_REMOTE_DISABLE", "warning");
				return;
			}
			if (!canServe()) {
				ctx.ui.notify("non-interactive session · set PI_REMOTE_ALLOW_NON_TUI=1 to serve", "warning");
				return;
			}
			// Rule 6: already the owner says so and changes nothing.
			if (server && boundAddress) {
				ctx.ui.notify(`already serving ${boundAddress.host}:${boundAddress.port}`, "info");
				return;
			}
			// A release may have stopped the tick; serving has to bring it back.
			startTicking();
			const result = await takeoverAndBind(host, port, TAKEOVER_WINDOW_MS);
			if (result.ok && boundAddress) {
				if (boundAddress.host === FALLBACK_HOST) {
					// Rule 8: report the address that was actually bound.
					ctx.ui.notify(
						`serving ${FALLBACK_HOST}:${port} · ${host} is unavailable, the phone cannot reach this`,
						"warning",
					);
				} else {
					const cwd = context?.cwd ?? process.cwd();
					const name = sessionName();
					ctx.ui.notify(
						`serving ${boundAddress.host}:${boundAddress.port} as ${cwd}${name ? ` ${name}` : ""}`,
						"info",
					);
				}
				return;
			}
			if (result.code === "released") {
				ctx.ui.notify(`released ${host}:${port} before the bind landed`, "warning");
				return;
			}
			const holder = holderFor(port);
			ctx.ui.notify(
				holder
					? `could not bind ${host}:${port} after 5s · held by pid ${holder.pid} (${holder.comm})`
					: `could not bind ${host}:${port} after 5s`,
				"warning",
			);
		},
	});

	pi.registerCommand("pair", {
		description: "pair the phone: show a QR carrying a one-time code (2 min, single use)",
		handler: async (args, ctx) => {
			const host = configuredHost();
			const port = configuredPort();
			const label = (args.trim() || "phone").slice(0, 40);
			if (process.env.PI_REMOTE_DISABLE === "1") {
				ctx.ui.notify("bridge disabled by PI_REMOTE_DISABLE", "warning");
				return;
			}
			if (!canServe()) {
				ctx.ui.notify("non-interactive session · set PI_REMOTE_ALLOW_NON_TUI=1 to serve", "warning");
				return;
			}

			// `/pair` runs the same takeover as `/pi-remote` before minting a code, so
			// pairing from a second pane does not fail with "run /pair there".
			let tookOverFrom: number | undefined;
			if (!server) {
				// Pairing bootstraps the bridge: there is no token yet, and the tick
				// deliberately does nothing until one exists.
				startTicking();
				const lease = readLease();
				if (
					lease &&
					lease.pid !== process.pid &&
					leaseOwnerAlive(lease) &&
					!leaseIsStale(lease, Date.now(), staleMs)
				) {
					tookOverFrom = lease.pid;
				}
				const result = await takeoverAndBind(host, port, TAKEOVER_WINDOW_MS);
				if (!result.ok) {
					const holder = holderFor(port, readLease());
					ctx.ui.notify(
						holder
							? `${host}:${port} is held by pid ${holder.pid} (${holder.comm}), not a pi session`
							: `could not bind ${host}:${port}`,
						"warning",
					);
					return;
				}
			}
			if (!server || !boundAddress) {
				ctx.ui.notify(`could not bind ${host}:${port}`, "warning");
				return;
			}
			// Rule 8 and the command table: a phone cannot reach loopback, so pairing
			// refuses there even though the bridge itself keeps serving.
			if (boundAddress.host === FALLBACK_HOST) {
				ctx.ui.notify("the phone cannot reach 127.0.0.1", "warning");
				return;
			}
			if (tookOverFrom !== undefined) {
				ctx.ui.notify(`took the bridge over from pid ${tookOverFrom}`, "info");
			}
			const { code, expiresAt } = mintPairCode(label);
			const pair = pairHost();
			// The QR must name the bound address, not the configured one; the MagicDNS
			// name is preferred when Tailscale can be asked.
			const qrHost = pair.source === "fallback" ? boundAddress.host : pair.host;
			const baseUrl = `http://${qrHost}:${boundAddress.port}`;
			const payload = `pi-remote://pair?v=1&u=${Buffer.from(baseUrl, "utf8").toString("base64url")}&c=${code}`;
			const qr = spawnSync("qrencode", ["-t", "UTF8", "-m", "1", "-o", "-"], {
				input: payload,
				encoding: "utf8",
			});
			const lines = (qr.stdout || "").replace(/\n+$/, "").split("\n").filter((line) => line.length > 0);
			const image = "/tmp/pi-remote-pair.png";
			const png = spawnSync("qrencode", ["-s", "8", "-m", "2", "-o", image], {
				input: payload,
				encoding: "utf8",
			});
			const pngShown = !png.error && png.status === 0;
			const opened = pngShown ? openImage(image) : { ok: false, viewer: "" };
			// pi caps widget content at MAX_WIDGET_LINES (10) and appends
			// "... (widget truncated)" past that. A QR for this payload needs ~23 text
			// lines, so it only belongs in the widget when it fits beside the caption.
			const caption = [
				`pairing "${label}" · code valid until ${expiresAt} · single use`,
				opened.ok
					? `scan the QR in the ${opened.viewer} window · file: ${image}`
					: `open ${image} on this screen to scan it`,
			];
			const terminalQr = lines.length > 0 && lines.length + caption.length <= 10;
			ctx.ui.setWidget(PAIR_WIDGET_ID, terminalQr ? [...lines, "", ...caption] : caption);
			setTimeout(() => ctx.ui.setWidget(PAIR_WIDGET_ID, undefined), PAIR_TTL_MS + 1000).unref?.();
			audit({
				event: "pair_shown",
				label,
				host: qrHost,
				hostSource: pair.source,
				port: boundAddress.port,
				terminalQr,
				png: pngShown,
				viewer: opened.viewer || null,
			});
			const fallbackNote = pair.source === "fallback" ? " · raw address: the app may refuse cleartext" : "";
			ctx.ui.notify(
				pngShown
					? opened.ok
						? `pi-remote: QR for "${label}" is in the ${opened.viewer} window (valid until ${expiresAt})${fallbackNote}`
						: `pi-remote: QR written to ${image} — open it yourself to scan (valid until ${expiresAt})${fallbackNote}`
					: "pi-remote: qrencode unavailable — install it, or pair manually with a token from pi-remote-token",
				pngShown ? "info" : "warning",
			);
		},
	});
}
