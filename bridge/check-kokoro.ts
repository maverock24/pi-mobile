/**
 * Kokoro render-engine check.
 *
 * Kokoro is the only engine a talk may be rendered with: the human asked for it
 * by name, and a silent fall back to another engine is the exact failure this
 * guards. It asserts the command the bridge would spawn points at the Kokoro
 * install on this machine, that the engine probe passes, and that a missing
 * renderer or interpreter comes back as an actionable sentence rather than an
 * empty string. It reads the real install, so it proves the live setup rather
 * than a fixture.
 *
 * Run it directly, node strips the types:
 *   node bridge/check-kokoro.ts
 */

import * as fs from "node:fs";
import * as path from "node:path";
import { DEFAULT_TALK_VOICE, kokoroCommand, kokoroProblem, kokoroPythonPath, kokoroScriptPath } from "./lib/talks.ts";

let failures = 0;

function check(name: string, ok: boolean, detail: string): void {
	if (ok) {
		console.log(`ok   ${name}`);
	} else {
		failures += 1;
		console.log(`FAIL ${name}: ${detail}`);
	}
}

// Point the module at the repo copy of the renderer, which is what install.sh
// would copy beside the skill, so the check does not depend on an install step.
const repoRenderer = path.resolve(import.meta.dirname, "skills", "talk", "render-kokoro.py");
const realPython = path.join(process.env.HOME || "", ".local", "share", "kokoro", "venv", "bin", "python");
process.env.PI_REMOTE_KOKORO = repoRenderer;

check("the renderer script exists", fs.existsSync(repoRenderer), repoRenderer);
check("the Kokoro interpreter exists", fs.existsSync(realPython), realPython);

// 1. The command is Kokoro: the interpreter is the venv python and the renderer
// gets the whole script, the output path and the voice.
const command = kokoroCommand("/tmp/script.txt", "/tmp/out.mp3", "af_heart");
check("command python is the Kokoro interpreter", command.python === kokoroPythonPath(), command.python);
check(
	"command argv is renderer, script, mp3, voice",
	command.args.length === 4 &&
		command.args[0] === kokoroScriptPath() &&
		command.args[1] === "/tmp/script.txt" &&
		command.args[2] === "/tmp/out.mp3" &&
		command.args[3] === "af_heart",
	JSON.stringify(command.args),
);
check("the default voice is a Kokoro voice", DEFAULT_TALK_VOICE === "af_heart", DEFAULT_TALK_VOICE);

// 2. A working install reports no problem, which is what lets a render start.
const healthy = kokoroProblem();
check("a healthy install reports no problem", healthy === undefined, String(healthy));

// 3. A missing renderer fails loudly and names the path it checked.
process.env.PI_REMOTE_KOKORO = "/nonexistent/render-kokoro.py";
const missingRenderer = kokoroProblem();
check(
	"a missing renderer is an actionable error",
	typeof missingRenderer === "string" && missingRenderer.includes("/nonexistent/render-kokoro.py"),
	String(missingRenderer),
);

// 4. A missing interpreter fails loudly too.
process.env.PI_REMOTE_KOKORO = repoRenderer;
process.env.PI_REMOTE_KOKORO_PYTHON = "/nonexistent/venv/bin/python";
const missingPython = kokoroProblem();
check(
	"a missing interpreter is an actionable error",
	typeof missingPython === "string" && missingPython.includes("/nonexistent/venv/bin/python"),
	String(missingPython),
);
delete process.env.PI_REMOTE_KOKORO_PYTHON;

if (failures > 0) {
	console.log(`${failures} check(s) failed`);
	process.exit(1);
}
console.log("all checks passed");
