#!/usr/bin/env bash
# Install the bridge into the pi agent directory.
#
# Copies the repo source to the paths pi auto-discovers, prints where each file
# went, the sha256 of what it wrote and the git revision it came from.
#
# Run this yourself after editing bridge/. It never runs itself and it never
# touches ~/.config/pi-remote: the lease, takeover records, tokens and the audit
# log there belong to the running bridge, and rewriting them from an install
# would evict whoever is serving.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$here" rev-parse --show-toplevel 2>/dev/null || echo "$here/..")"
agent_dir="${PI_AGENT_DIR:-$HOME/.pi/agent}"

# The layout under bridge/ mirrors the installed layout, so `../lib/x.ts`
# resolves the same in the repo as it does under ~/.pi/agent. Adding a file to
# bridge/lib needs a line here as well.
sources=(
	"$here/extensions/pi-remote.ts:$agent_dir/extensions/pi-remote.ts"
	"$here/lib/remote-question.ts:$agent_dir/lib/remote-question.ts"
	"$here/lib/lease.ts:$agent_dir/lib/lease.ts"
)

missing=0
for pair in "${sources[@]}"; do
	src="${pair%%:*}"
	if [[ ! -f "$src" ]]; then
		printf 'install: missing source %s\n' "$src" >&2
		missing=1
	fi
done
if [[ "$missing" -ne 0 ]]; then
	exit 1
fi

for pair in "${sources[@]}"; do
	src="${pair%%:*}"
	dst="${pair#*:}"
	mkdir -p "$(dirname "$dst")"
	cp "$src" "$dst"
	printf '%s\n  -> %s\n  sha256 %s\n' "$src" "$dst" "$(sha256sum "$dst" | cut -d' ' -f1)"
done

printf 'git revision %s\n' "$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)"
if [[ -n "$(git -C "$repo_root" status --porcelain 2>/dev/null)" ]]; then
	printf 'warning: the repo has uncommitted changes; the revision above is not what was copied\n' >&2
fi
