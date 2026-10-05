/**
 * Lease and takeover records for the pi-remote bridge.
 *
 * The successful bind on `host:port` is the only authority; these records are
 * bookkeeping around it. A lease is written only after a bind succeeds. A
 * takeover record is how one session asks the current owner to drop the address.
 * Both are keyed by the address they describe, so two panes serving two ports
 * never interfere.
 *
 * This module is deliberately pure: no fs, no clock, no process. Every decision
 * is a function of the values handed to it, so the state machine in
 * docs/SESSION-MANAGEMENT.md can be tested without a socket.
 */

export type LeaseRecord = {
	pid: number;
	/** The configured address the records are keyed by. */
	host: string;
	port: number;
	/** The address the bind actually took, which differs after the loopback fallback. */
	boundHost: string;
	boundPort: number;
	sessionId: string | null;
	sessionName: string | null;
	cwd: string;
	mode: string;
	sessionFile: string | null;
	claimedAt: string;
	heartbeatAt: string;
};

export type TakeoverRecord = {
	pid: number;
	at: string;
	nonce: string;
};

/** How long a takeover record asks the previous owner to keep its head down. */
export const TAKEOVER_TTL_MS = 5_000;

/** One lease filename per address. The state directory holds one of these per bound address. */
export function leaseFileName(host: string, port: number): string {
	return `owner-${host}-${port}.json`;
}

/** The takeover filename for an address. This exact name is what the watch reacts to. */
export function takeoverFileName(host: string, port: number): string {
	return `takeover-${host}-${port}.json`;
}

/**
 * The watcher filter. True only for the exact takeover filename of this address;
 * every other write in the state directory (the lease and its temp, tokens.json,
 * the audit log) is ignored so a heartbeat or a rotation cannot retrigger the
 * takeover handler into a loop.
 */
export function isTakeoverFile(filename: string | null | undefined, host: string, port: number): boolean {
	return filename === takeoverFileName(host, port);
}

function timeOf(iso: string | null | undefined): number | null {
	if (typeof iso !== "string") return null;
	const parsed = Date.parse(iso);
	return Number.isFinite(parsed) ? parsed : null;
}

/** True when the lease is missing, unparsable, or its heartbeat is older than the window. */
export function leaseIsStale(
	lease: LeaseRecord | null | undefined,
	now: number,
	staleMs: number,
): boolean {
	if (!lease) return true;
	const beat = timeOf(lease.heartbeatAt);
	if (beat === null) return true;
	return now - beat > staleMs;
}

/**
 * Rule 2. A heartbeat may refresh a lease that names this process, or write a
 * missing one while this process is serving. A lease naming any other pid is
 * never touched, alive or not: an owner only ever writes its own record, so it
 * can never revert another session's.
 */
export function mayRefreshLease(
	lease: LeaseRecord | null | undefined,
	pid: number,
	serving: boolean,
): boolean {
	if (!lease) return serving;
	return lease.pid === pid;
}

/**
 * Rules 3 and 4. A takeover record asks the named other pid to drop the address.
 * It is valid only while fresh: an old one is a leftover that must not evict the
 * current owner. A record this process wrote itself is never acted on, so a
 * command that is not serving cannot make itself drop.
 */
export function takeoverIsValid(
	takeover: TakeoverRecord | null | undefined,
	now: number,
	selfPid: number,
	ttlMs: number = TAKEOVER_TTL_MS,
): boolean {
	if (!takeover || typeof takeover.pid !== "number") return false;
	if (takeover.pid === selfPid) return false;
	const at = timeOf(takeover.at);
	if (at === null) return false;
	const age = now - at;
	return age >= 0 && age <= ttlMs;
}

export type BridgeState = "stopped" | "waiting" | "binding" | "serving" | "blocked";

export type StateDecision = {
	state: BridgeState;
	/** The caller starts a bind when this is true and no server is up. */
	shouldBind: boolean;
};

export type StateInputs = {
	disabled: boolean;
	/** This process currently holds the bind. */
	serving: boolean;
	/** A bind attempt is in flight. */
	binding: boolean;
	selfPid: number;
	lease: LeaseRecord | null | undefined;
	/** The lease names another pid that is still alive (process.kill(pid, 0) worked). */
	ownerAlive: boolean;
	/** A fresh takeover record names another pid. */
	takeoverActive: boolean;
	/** The port is held by a process that is neither this session nor a known pi owner. */
	portHeldByForeign: boolean;
	now: number;
	staleMs: number;
};

/**
 * The one decision the extension makes on every tick, kept pure so the
 * transitions in the spec can be tested without a socket. "waiting" with
 * shouldBind true means the address looks free and this session may take it;
 * the caller moves to "binding" when it starts the listen.
 */
export function decideState(input: StateInputs): StateDecision {
	if (input.disabled) return { state: "stopped", shouldBind: false };
	if (input.serving) {
		// Rule 3: a fresh takeover naming another pid outranks holding the bind. The
		// tick calls this while serving, so the rule lives in one place rather than in
		// the watcher, the bind callback and the loop separately.
		return input.takeoverActive
			? { state: "waiting", shouldBind: false }
			: { state: "serving", shouldBind: false };
	}
	if (input.binding) return { state: "binding", shouldBind: false };
	// A fresh takeover naming another pid: stay down until it expires or binds.
	if (input.takeoverActive) return { state: "waiting", shouldBind: false };
	const lease = input.lease;
	if (
		lease &&
		lease.pid !== input.selfPid &&
		input.ownerAlive &&
		!leaseIsStale(lease, input.now, input.staleMs)
	) {
		return { state: "waiting", shouldBind: false };
	}
	if (input.portHeldByForeign) return { state: "blocked", shouldBind: false };
	return { state: "waiting", shouldBind: true };
}
