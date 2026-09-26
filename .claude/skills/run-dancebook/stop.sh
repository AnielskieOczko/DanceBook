#!/usr/bin/env bash
# Stop the DanceBook started by start.sh on PORT (default 8080). Postgres keeps running.
set -uo pipefail
PORT=${PORT:-8080}
RUN_DIR=${RUN_DIR:-$(git rev-parse --show-toplevel)/build/dancebook-run}
# The pid is the Gradle wrapper; the JVM serving the port is a child, so kill by port.
pids=$(lsof -ti:"$PORT" -sTCP:LISTEN)
[ -n "$pids" ] && kill $pids
[ -f "$RUN_DIR/app-$PORT.pid" ] && kill "$(cat "$RUN_DIR/app-$PORT.pid")" 2>/dev/null
rm -f "$RUN_DIR/app-$PORT.pid"
for _ in $(seq 1 20); do lsof -ti:"$PORT" -sTCP:LISTEN >/dev/null || { echo "stopped :$PORT"; exit 0; }; sleep 0.5; done
echo "still listening on :$PORT" >&2; exit 1
