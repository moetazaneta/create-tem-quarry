#!/usr/bin/env bash
# Drives the running dev client (./gradlew runClient) on macOS: types chat commands,
# takes an F2 screenshot with the HUD hidden, then puts the player and focused app
# back. Prints the screenshot path and every chat line the run produced. Warns if
# the client is older than the code; run mc-refresh.sh first in that case.
#
# Usage: mc-shot.sh [--wait SECONDS] [--keep-hud] [--no-restore] [COMMAND | @wait N]...
#   COMMAND   a chat command, e.g. "/setblock 1000 0 1000 create_tem_quarry:quarry"
#   @wait N   pause N seconds between commands (e.g. after a /tp so chunks load)
set -euo pipefail

source "$(dirname "$0")/lib.sh"

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

pid="$(client_pid)"
[[ -n "$pid" ]] || die "dev client is not running. Start it with mc-refresh.sh."
stale="$(changed_files "$pid")"
[[ -f "$LOG" ]] || die "no log at $LOG"

remember_focus
focus "$pid"
sleep 1.5
start="$(offset)"

pos_line="$(ensure_playing)"
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
		run_cmd "$c"
	fi
done
sleep "$settle"

((hide_hud)) && { key 122; sleep 0.6; } # F1
from="$(offset)"
key 120 # F2
shot_line="$(wait_chat "$from" 10 'Saved screenshot as')" || shot_line=""
((hide_hud)) && key 122

if ((restore)) && [[ -n "$pos" ]]; then
	run_cmd "/tp @s $pos ${rot:-~ ~}"
fi

echo "--- chat ---"
chat_since "$start"
echo "------------"
[[ -n "$shot_line" ]] || die "screenshot was not saved"
if [[ -n "$stale" ]]; then
	echo "WARNING: the client is older than these files, so the screenshot may not show them:" >&2
	sed 's/^/  /' <<<"$stale" >&2
fi
echo "SCREENSHOT: $RUN/screenshots/$(sed -E 's/.*Saved screenshot as (.*)$/\1/' <<<"$shot_line")"
