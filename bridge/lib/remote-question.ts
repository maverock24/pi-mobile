/**
 * Remote question channel.
 *
 * The TUI question widgets can also be answered from the phone. A widget
 * publishes itself to `question-pending.json` and polls for an answer written by
 * the pi-remote bridge into `question-answer.json`. Files are used on purpose:
 * the tools and the bridge are separate extensions, and a shared file protocol
 * cannot drift the way shared in-process state can.
 *
 * Both names carry the process id. A widget and the bridge that serves it are
 * the same pi process, and every other pi session on the machine is a different
 * one. Without that, the two files are machine-wide: a bridge reports whichever
 * session asked last as its own question, and the answer is written for a widget
 * the phone is not attached to.
 *
 * Directory: $PI_REMOTE_DIR or ~/.local/share/pi-remote
 *
 * Deliberately dependency-free so every question tool can import it.
 */

import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

export type RemoteOption = {
	value: string;
	label: string;
	description?: string;
};

export type RemoteAnswerValue = {
	value: string;
	label: string;
	wasCustom: boolean;
	index?: number;
};

export type RemoteQuestion = {
	id: string;
	label: string;
	prompt: string;
	options: RemoteOption[];
	allowOther: boolean;
	answer: RemoteAnswerValue | null;
};

export type RemotePending = {
	id: string;
	tool: string;
	title: string;
	multiple: boolean;
	allowOther: boolean;
	questions: RemoteQuestion[];
	updatedAt: string;
};

export type RemoteAnswer = {
	id: string;
	questionId?: string;
	value?: string;
	custom?: boolean;
	cancel?: boolean;
	submit?: boolean;
};

const POLL_MS = 250;

export function remoteDir(): string {
	return process.env.PI_REMOTE_DIR || path.join(os.homedir(), ".local", "share", "pi-remote");
}

function pendingPath(): string {
	return path.join(remoteDir(), `question-pending-${process.pid}.json`);
}

function answerPath(): string {
	return path.join(remoteDir(), `question-answer-${process.pid}.json`);
}

function ensureDir(): void {
	fs.mkdirSync(remoteDir(), { recursive: true, mode: 0o700 });
}

function writeAtomic(target: string, payload: unknown): void {
	ensureDir();
	const temp = path.join(remoteDir(), `.${path.basename(target)}.tmp`);
	fs.writeFileSync(temp, JSON.stringify(payload), { mode: 0o600 });
	fs.renameSync(temp, target);
}

export function readPending(): RemotePending | null {
	try {
		const parsed = JSON.parse(fs.readFileSync(pendingPath(), "utf8")) as RemotePending;
		return parsed?.id ? parsed : null;
	} catch {
		return null;
	}
}

/** Called by the bridge when the phone answers. */
export function writeAnswer(answer: Omit<RemoteAnswer, "id"> & { id?: string }): boolean {
	const pending = readPending();
	if (!pending) {
		return false;
	}
	writeAtomic(answerPath(), { ...answer, id: pending.id });
	return true;
}

function readAnswer(): RemoteAnswer | null {
	try {
		const parsed = JSON.parse(fs.readFileSync(answerPath(), "utf8")) as RemoteAnswer;
		return parsed?.id ? parsed : null;
	} catch {
		return null;
	}
}

function removeIf(pathname: string, mine: (value: unknown) => boolean): void {
	try {
		const parsed = JSON.parse(fs.readFileSync(pathname, "utf8"));
		if (mine(parsed)) {
			fs.rmSync(pathname, { force: true });
		}
	} catch {
		// missing or unreadable: nothing to clean up
	}
}

/**
 * Match a remote answer against the offered options. The app sends the option
 * value, but a typed answer arrives as free text and should still select an
 * option when it names one ("green" should pick "Green").
 */
export function matchOption<T extends { value: string; label: string }>(
	options: T[],
	raw: string,
): T | undefined {
	const needle = raw.trim().toLowerCase();
	if (!needle) {
		return undefined;
	}
	return (
		options.find((option) => option.value.trim().toLowerCase() === needle) ??
		options.find((option) => option.label.trim().toLowerCase() === needle)
	);
}

export type RemoteChannel = {
	/** Identifier of this widget instance, used to match answers. */
	id: string;
	/** Publish or refresh the pending question. */
	publish(state: Omit<RemotePending, "id" | "tool" | "updatedAt">): void;
	/** Start polling for answers. */
	start(handlers: { onAnswer: (answer: RemoteAnswer) => void; onCancel: () => void }): void;
	stop(): void;
	/** Remove the pending file (and any answer addressed to us). */
	clear(): void;
};

export function createRemoteChannel(tool: string): RemoteChannel {
	const id = `q-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
	let timer: NodeJS.Timeout | undefined;

	return {
		id,

		publish(state) {
			try {
				writeAtomic(pendingPath(), { id, tool, updatedAt: new Date().toISOString(), ...state });
			} catch {
				// A missing pending file only costs the remote option, never the TUI.
			}
		},

		start(handlers) {
			if (timer) {
				return;
			}
			timer = setInterval(() => {
				const answer = readAnswer();
				if (!answer || answer.id !== id) {
					return;
				}
				removeIf(answerPath(), (value) => (value as RemoteAnswer)?.id === id);
				try {
					if (answer.cancel) {
						handlers.onCancel();
					} else {
						handlers.onAnswer(answer);
					}
				} catch {
					// handler decided the widget was already gone
				}
			}, POLL_MS);
			timer.unref?.();
		},

		stop() {
			if (timer) {
				clearInterval(timer);
				timer = undefined;
			}
		},

		clear() {
			removeIf(pendingPath(), (value) => (value as RemotePending)?.id === id);
			removeIf(answerPath(), (value) => (value as RemoteAnswer)?.id === id);
		},
	};
}
