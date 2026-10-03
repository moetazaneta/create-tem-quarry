#!/usr/bin/env bash
# Brings the dev client up to date with the working tree. Looks at files changed
# since the client started (or since the last refresh):
#   only assets/  -> processResources, then F3+T          (seconds, world stays open)
#   data/         -> processResources, then /reload       (seconds, world stays open)
#   anything else -> build, quit the client, relaunch it straight into the world (~40s)
# Starts the client if it isn't running.
#
# Usage: mc-refresh.sh [--check]
#   --check   only print what changed and what a refresh would do
# MC_WORLD picks the save folder to open (default: the most recently played one).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

check=0
[[ "${1:-}" == "--check" ]] && check=1

world="${MC_WORLD:-$(basename "$(ls -td "$RUN"/saves/*/ 2>/dev/null | head -1)")}"

launch() {
	[[ -n "$world" && -d "$RUN/saves/$world" ]] || die "no world to open. Set MC_WORLD to a folder in run/saves."
	local launch_log="$RUN/logs/mc-refresh-launch.log" marker old_log gradle_pid i crash
	marker="$(mktemp)"
	old_log="$(stat -f %i "$LOG" 2>/dev/null || echo none)"
	echo "Launching the client into '$world' (log: $launch_log)..."
	cd "$ROOT"
	nohup ./gradlew runClient --args="--quickPlaySingleplayer \"$world\"" >"$launch_log" 2>&1 &
	gradle_pid=$!
	disown
	# latest.log is replaced when the new client starts; wait for the player to join.
	for ((i = 0; i < 600; i++)); do
		if ! kill -0 "$gradle_pid" 2>/dev/null; then
			crash="$(find "$RUN/crash-reports" -name '*.txt' -newer "$marker" 2>/dev/null | head -1)"
			if [[ -n "$crash" ]]; then
				grep -E '^(Description|Caused by|[A-Za-z.]+(Exception|Error):)' "$crash" | head -8 >&2
				die "the client crashed on startup and is NOT running. Crash report: $crash"
			fi
			tail -30 "$launch_log" >&2
			die "runClient failed before the world loaded; the client is NOT running"
		fi
		if [[ "$(stat -f %i "$LOG" 2>/dev/null)" != "$old_log" ]] && grep -aq 'joined the game' "$LOG"; then
			sleep 5 # let chunks and JEI finish loading
			touch "$STAMP"
			echo "Client is running the current code."
			return 0
		fi
		sleep 1
	done
	die "timed out waiting for the world to load"
}

quit_client() {
	local pid="$1" i
	echo "Quitting the client (pid $pid)..."
	focus "$pid"
	sleep 1
	osa 'tell application "System Events" to key code 12 using command down' # Cmd+Q saves and quits
	for ((i = 0; i < 60; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
	kill -TERM "$pid" # the client's shutdown hook still saves the world
	for ((i = 0; i < 30; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done
	die "client (pid $pid) did not quit"
}

pid="$(client_pid)"
if [[ -z "$pid" ]]; then
	echo "Client is not running."
	((check)) && { echo "Refresh would: launch it into '$world'."; exit 0; }
	remember_focus
	launch
	exit 0
fi

changed="$(changed_files "$pid")"
if [[ -z "$changed" ]]; then
	echo "Client is up to date."
	exit 0
fi
echo "Changed since the client started or last refreshed:"
sed 's/^/  /' <<<"$changed"

if grep -qvE '^src/main/resources/(assets|data)/' <<<"$changed"; then
	((check)) && { echo "Refresh would: build, quit the client and relaunch it (~40s)."; exit 0; }
	# Build first, so a compile error leaves the old client running.
	(cd "$ROOT" && ./gradlew -q classes processResources) || die "build failed; the old client is still running"
	remember_focus
	quit_client "$pid"
	launch
	exit 0
fi

if ((check)); then
	grep -q '^src/main/resources/assets/' <<<"$changed" && echo "Refresh would: processResources, then F3+T."
	grep -q '^src/main/resources/data/' <<<"$changed" && echo "Refresh would: processResources, then /reload."
	exit 0
fi

(cd "$ROOT" && ./gradlew -q processResources) || die "processResources failed"
remember_focus
focus "$pid"
sleep 1.5
ensure_playing >/dev/null
if grep -q '^src/main/resources/assets/' <<<"$changed"; then
	echo "Reloading resource packs (F3+T)..."
	osa 'tell application "System Events"
		key down 99
		delay 0.1
		key code 17
		delay 0.1
		key up 99
	end tell'
	sleep 6
fi
if grep -q '^src/main/resources/data/' <<<"$changed"; then
	echo "Reloading data packs (/reload)..."
	from="$(offset)"
	send "/reload"
	wait_chat "$from" 30 'Reloading!' >/dev/null || die "/reload got no response"
	sleep 3
fi
touch "$STAMP"
echo "Client is running the current code."
