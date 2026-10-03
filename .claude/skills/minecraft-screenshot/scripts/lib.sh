# Shared helpers for mc-shot.sh and mc-refresh.sh. Source it, don't run it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
RUN="$ROOT/run"
LOG="$RUN/logs/latest.log"
# Touched after each refresh, so already-reloaded changes aren't picked up again.
STAMP="$RUN/.mc-refresh-stamp"

die() { echo "$(basename "$0" .sh): $*" >&2; exit 1; }

client_pid() { pgrep -f 'fabric.dli.env=client' | head -1 || true; }

osa() { osascript -e "$1" >/dev/null; }
key() { osa "tell application \"System Events\" to key code $1"; }
focus() { osa "tell application \"System Events\" to set frontmost of (first process whose unix id is $1) to true"; }

# Puts the app that was in front back when the script exits.
remember_focus() {
	prev_front="$(osascript -e 'tell application "System Events" to get unix id of first process whose frontmost is true' 2>/dev/null || true)"
	trap '[[ -n "$prev_front" ]] && focus "$prev_front" 2>/dev/null || true' EXIT
}

# Minecraft ignores Cmd+V from System Events, so commands are typed. The typed
# characters follow the active input source, which has to be a Latin layout.
send() {
	osascript - "$1" >/dev/null <<'APPLESCRIPT'
on run argv
	tell application "System Events"
		key code 17 -- T opens chat
		delay 0.6
		keystroke (item 1 of argv)
		delay 0.4
		key code 36 -- Enter
	end tell
end run
APPLESCRIPT
	sleep 0.5
}

# Sends a command and waits for its chat feedback. Enter sometimes gets dropped
# and leaves the command sitting in an open chat box, so press it again once if
# nothing comes back (Enter does nothing in game when chat is closed).
run_cmd() {
	local from
	from="$(offset)"
	send "$1"
	wait_chat "$from" 3 . >/dev/null && return 0
	key 36
	wait_chat "$from" 3 . >/dev/null && return 0
	echo "warning: no chat feedback for: $1" >&2
}

offset() { wc -c <"$LOG" | tr -d ' '; }
chat_since() { tail -c +"$(($1 + 1))" "$LOG" | grep -a '\[CHAT\]' | sed 's/.*\[CHAT\] //' || true; }

# Waits up to $2 seconds for a chat line matching $3 after byte offset $1.
wait_chat() {
	local from="$1" timeout="$2" pattern="$3" i
	for ((i = 0; i < timeout * 4; i++)); do
		chat_since "$from" | grep -E "$pattern" | tail -1 && return 0
		sleep 0.25
	done
	return 1
}

# Lists files in the project that changed after the client (pid $1) started, or
# after the last refresh if that was later.
changed_files() {
	local ref
	ref="$(mktemp)"
	touch -t "$(date -j -f '%a %b %e %T %Y' "$(ps -o lstart= -p "$1" | xargs)" +%Y%m%d%H%M.%S)" "$ref"
	if [[ -f "$STAMP" && "$STAMP" -nt "$ref" ]]; then touch -r "$STAMP" "$ref"; fi
	(cd "$ROOT" && find src build.gradle gradle.properties settings.gradle -type f -newer "$ref" 2>/dev/null) || true
	rm -f "$ref"
}

# Makes sure the focused client is in a world with no menu open, and prints the
# player's position line. If the probe gets no answer, a menu (usually the pause
# screen) is open: press Esc once and try again.
ensure_playing() {
	local from
	from="$(offset)"
	send "/data get entity @s Pos"
	wait_chat "$from" 3 'entity data: \[' && return 0
	key 53
	sleep 1
	from="$(offset)"
	send "/data get entity @s Pos"
	wait_chat "$from" 3 'entity data: \[' ||
		die "no response to chat commands. Is a world open, with cheats on and the chat closed?"
}
