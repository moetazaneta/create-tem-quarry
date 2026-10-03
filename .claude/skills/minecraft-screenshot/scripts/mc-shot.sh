#!/usr/bin/env bash
# Drives the running dev client (./gradlew runClient) on macOS: runs chat commands,
# takes an F2 screenshot with the HUD hidden, then puts the player and focused app
# back. Prints the screenshot path and every chat line the run produced.
#
# Usage: mc-shot.sh [--wait SECONDS] [--keep-hud] [--no-restore] [COMMAND | @wait N]...
#   COMMAND   a chat command, e.g. "/setblock 1000 0 1000 create_tem_quarry:quarry"
#   @wait N   pause N seconds between commands (e.g. after a /tp so chunks load)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
LOG="$ROOT/run/logs/latest.log"
SHOTS="$ROOT/run/screenshots"

settle=2
hide_hud=1
restore=1
commands=()
while (($#)); do
	case "$1" in
		--wait) settle="$2"; shift 2 ;;
		--keep-hud) hide_hud=0; shift ;;
		--no-restore) restore=0; shift ;;
		*) commands+=("$1"); shift ;;
	esac
done

die() { echo "mc-shot: $*" >&2; exit 1; }

pid="$(pgrep -f 'fabric.dli.env=client' | head -1 || true)"
[[ -n "$pid" ]] || die "dev client is not running. Start it with ./gradlew runClient and open a world with cheats on."
[[ -f "$LOG" ]] || die "no log at $LOG"

osa() { osascript -e "$1" >/dev/null; }
key() { osa "tell application \"System Events\" to key code $1"; }

# Minecraft ignores Cmd+V from System Events, so commands are typed. The typed
# characters follow the active input source, which has to be a Latin layout.
send() {
	key 17 # T opens chat
	sleep 0.6
	osascript - "$1" >/dev/null <<'APPLESCRIPT'
on run argv
	tell application "System Events" to keystroke (item 1 of argv)
end run
APPLESCRIPT
	sleep 0.3
	key 36 # Enter
	sleep 0.5
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

prev_front="$(osascript -e 'tell application "System Events" to get unix id of first process whose frontmost is true' 2>/dev/null || true)"
cleanup() {
	[[ -n "$prev_front" ]] && osa "tell application \"System Events\" to set frontmost of (first process whose unix id is $prev_front) to true" || true
}
trap cleanup EXIT

osa "tell application \"System Events\" to set frontmost of (first process whose unix id is $pid) to true"
sleep 1.5
start="$(offset)"

# Probe with a query that also records where the player is. If nothing answers,
# a menu (usually the pause screen) is open: press Esc once and try again.
probe() {
	local from
	from="$(offset)"
	send "/data get entity @s Pos"
	wait_chat "$from" 3 'entity data: \['
}
if ! pos_line="$(probe)"; then
	key 53
	sleep 1
	pos_line="$(probe)" || die "no response to chat commands. Is a world open, with cheats on and the chat closed?"
fi
from="$(offset)"
send "/data get entity @s Rotation"
rot_line="$(wait_chat "$from" 3 'entity data: \[')" || rot_line=""

nums() { sed -E 's/.*\[(.*)\].*/\1/; s/[dfDF]//g; s/,//g'; }
pos="$(nums <<<"$pos_line")"
rot="$(nums <<<"$rot_line")"

for c in "${commands[@]+"${commands[@]}"}"; do
	if [[ "$c" == @wait* ]]; then
		sleep "${c#@wait }"
	else
		send "$c"
	fi
done
sleep "$settle"

((hide_hud)) && { key 122; sleep 0.6; } # F1
from="$(offset)"
key 120 # F2
shot_line="$(wait_chat "$from" 10 'Saved screenshot as')" || shot_line=""
((hide_hud)) && key 122

if ((restore)) && [[ -n "$pos" ]]; then
	send "/tp @s $pos ${rot:-~ ~}"
fi

echo "--- chat ---"
chat_since "$start"
echo "------------"
[[ -n "$shot_line" ]] || die "screenshot was not saved"
echo "SCREENSHOT: $SHOTS/$(sed -E 's/.*Saved screenshot as (.*)$/\1/' <<<"$shot_line")"
