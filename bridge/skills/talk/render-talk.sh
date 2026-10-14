#!/usr/bin/env bash
# Register a written script with the pi-remote bridge and render it to MP3.
#
# The render itself runs inside the bridge process, so this script only hands
# over the script, waits for the background Kokoro job, and prints the download
# link. Nothing here vendors or bundles Kokoro: the bridge invokes a locally
# installed renderer as a subprocess.
#
# Usage: render-talk.sh <title> <script-path> [voice]
#
# Env:
#   PI_REMOTE_URL         full bridge base url, when the loopback twin is off
#   PI_REMOTE_HOST        host when PI_REMOTE_URL is unset (default 127.0.0.1)
#   PI_REMOTE_PORT        port when PI_REMOTE_URL is unset (default 8787)
#   PI_REMOTE_TOKEN_FILE  the bridge token file; tokens.json sits beside it
#   PI_REMOTE_TALK_WAIT_SECONDS  how long to wait for the render (default 1800)
set -euo pipefail

title="${1:-}"
script="${2:-}"
voice="${3:-}"

if [[ -z "$title" || -z "$script" ]]; then
	printf 'usage: render-talk.sh <title> <script-path> [voice]\n' >&2
	exit 2
fi
if [[ ! -f "$script" ]]; then
	printf 'render-talk: no such script: %s\n' "$script" >&2
	exit 2
fi

# The bridge's loopback twin listens on the same port as the tailnet bind, so
# 127.0.0.1 is reachable from the laptop even when the tailnet address is not.
base="${PI_REMOTE_URL:-http://${PI_REMOTE_HOST:-127.0.0.1}:${PI_REMOTE_PORT:-8787}}"
token_dir="$(dirname "${PI_REMOTE_TOKEN_FILE:-$HOME/.config/pi-remote/token}")"
tokens_file="$token_dir/tokens.json"

# The script needs a Python interpreter anyway, so python3 is a safe dependency for
# reading the token and building JSON without hand-escaping the title.
token="$(python3 - "$tokens_file" <<'PY' || true
import json, sys
try:
    entries = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
for entry in entries:
    candidate = entry.get("token", "")
    if isinstance(candidate, str) and candidate:
        print(candidate)
        break
PY
)"
if [[ -z "$token" ]]; then
	printf 'render-talk: no device token in %s; pair the phone or check the bridge config\n' "$tokens_file" >&2
	exit 1
fi

payload="$(python3 - "$title" "$script" "$voice" <<'PY'
import json, sys
body = {"title": sys.argv[1], "scriptPath": sys.argv[2]}
if sys.argv[3]:
    body["voice"] = sys.argv[3]
print(json.dumps(body))
PY
)"

response="$(curl -sS -X POST "$base/api/talks" \
	-H "Authorization: Bearer $token" \
	-H 'content-type: application/json' \
	--data "$payload")"

id="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' <<<"$response" 2>/dev/null || true)"
if [[ -z "$id" ]]; then
	printf 'render-talk: the bridge refused the talk: %s\n' "$response" >&2
	exit 1
fi

minutes="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("estimatedMinutes","?"))' <<<"$response" 2>/dev/null || echo "?")"
printf 'render-talk: "%s" queued (id %s, about %s minutes of audio), rendering...\n' "$title" "$id" "$minutes" >&2

wait_seconds="${PI_REMOTE_TALK_WAIT_SECONDS:-1800}"
deadline=$(( $(date +%s) + wait_seconds ))
state="rendering"
while [[ "$state" == "rendering" ]]; do
	if (( $(date +%s) >= deadline )); then
		printf 'render-talk: still rendering after %s seconds; the talk id is %s, ask the bridge again later\n' "$wait_seconds" "$id" >&2
		exit 0
	fi
	sleep 5
	state="$(curl -sS "$base/api/talks" -H "Authorization: Bearer $token" | python3 -c '
import json, sys
target = sys.argv[1]
for talk in json.load(sys.stdin).get("talks", []):
    if talk.get("id") == target:
        print(talk.get("state", "rendering"))
        break
else:
    print("missing")
' "$id" 2>/dev/null || echo rendering)"
done

if [[ "$state" == "ready" ]]; then
	link="$(curl -sS -X POST "$base/api/talks/$id/link" \
		-H "Authorization: Bearer $token" \
		-H 'content-type: application/json' \
		--data '{}')"
	download_url="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("url",""))' <<<"$link" 2>/dev/null || true)"
	if [[ -z "$download_url" ]]; then
		printf 'render-talk: "%s" is ready but the bridge would not mint a link: %s\n' "$title" "$link" >&2
		exit 1
	fi
	printf 'render-talk ready: "%s", about %s minutes\nplay: %s\n' "$title" "$minutes" "$download_url"
	exit 0
fi

if [[ "$state" == "failed" ]]; then
	reason="$(curl -sS "$base/api/talks" -H "Authorization: Bearer $token" | python3 -c '
import json, sys
target = sys.argv[1]
for talk in json.load(sys.stdin).get("talks", []):
    if talk.get("id") == target:
        print(talk.get("error") or "the render failed")
        break
else:
    print("the render failed")
' "$id" 2>/dev/null || echo "the render failed")"
	printf 'render-talk: the render failed: %s\n' "$reason" >&2
	exit 1
fi

printf 'render-talk: talk %s ended in state "%s"\n' "$id" "$state" >&2
exit 1
