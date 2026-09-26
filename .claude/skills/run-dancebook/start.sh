#!/usr/bin/env bash
# Start DanceBook in the background and wait until it serves /login.
#
#   .claude/skills/run-dancebook/start.sh [repo-dir]
#
# repo-dir defaults to the git checkout you are in, so the same script starts main or an
# agy clone (../DanceBook-agy-<N>). Env:
#   PORT  (default 8080)       - use another port to run two checkouts side by side
#   DB    (default dancebook)  - any other name is created on first use as a COPY of
#                                dancebook, so a branch's Flyway migrations never touch
#                                your dev database
#   RUN_DIR (default <repo>/build/dancebook-run) - logs and pid files
# Real Google credentials are used if they are already in the environment; otherwise
# dummy values are passed, which is enough to boot and use everything except Drive and
# Google Calendar calls.
set -euo pipefail

REPO=$(cd "${1:-.}" && git rev-parse --show-toplevel)
PORT=${PORT:-8080}
DB=${DB:-dancebook}
RUN_DIR=${RUN_DIR:-$REPO/build/dancebook-run}
LOG="$RUN_DIR/app-$PORT.log"
mkdir -p "$RUN_DIR"

if lsof -ti:"$PORT" -sTCP:LISTEN >/dev/null; then
  echo "port $PORT is already in use - stop.sh first, or pick another PORT" >&2; exit 1
fi

# One shared Postgres container for every checkout. `docker compose up` from a clone would
# try to create a second container with the same name and port, so start it by name.
docker start dancebook-db >/dev/null 2>&1 || docker compose -f "$REPO/compose.yaml" up -d postgres
until docker exec dancebook-db pg_isready -q -U dancebook; do sleep 1; done

if [ "$DB" != dancebook ] && ! docker exec dancebook-db psql -U dancebook -d postgres -tAc \
    "select 1 from pg_database where datname='$DB'" | grep -q 1; then
  echo "creating database $DB as a copy of dancebook"
  docker exec dancebook-db createdb -U dancebook "$DB"
  docker exec dancebook-db sh -c "pg_dump -U dancebook dancebook | psql -q -U dancebook -d $DB" >/dev/null
fi

export DATABASE_URL="jdbc:postgresql://localhost:5432/$DB"
export DATABASE_USERNAME=dancebook DATABASE_PASSWORD=dancebook
export GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID:-dummy} GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET:-dummy}
export GOOGLE_REFRESH_TOKEN=${GOOGLE_REFRESH_TOKEN:-dummy} GOOGLE_DRIVE_FOLDER_ID=${GOOGLE_DRIVE_FOLDER_ID:-dummy}

cd "$REPO"
# spring-boot-docker-compose is on the dev classpath: left enabled, it replaces DATABASE_URL
# with the compose Postgres (always the dancebook db), and in a clone runs `compose up`.
nohup ./gradlew bootRun --args="--server.port=$PORT --spring.docker.compose.enabled=false" >"$LOG" 2>&1 &
echo $! >"$RUN_DIR/app-$PORT.pid"

for _ in $(seq 1 180); do
  if curl -sf -o /dev/null "http://localhost:$PORT/login"; then
    # Flyway logs the database it migrated; refuse to report success on the wrong one.
    if ! grep -Eq "Database: jdbc:postgresql://[^/]+/$DB([?]| |$)" "$LOG"; then
      echo "DanceBook came up on the WRONG database (wanted $DB):" >&2
      grep "Database: jdbc" "$LOG" >&2; exit 1
    fi
    echo "DanceBook ($REPO, db $DB) is up on http://localhost:$PORT - log: $LOG"; exit 0
  fi
  if ! kill -0 "$(cat "$RUN_DIR/app-$PORT.pid")" 2>/dev/null; then break; fi
  sleep 1
done
echo "DanceBook did not come up - last lines of $LOG:" >&2
grep -E "APPLICATION FAILED|Caused by|FAILED|error:" "$LOG" | tail -15 >&2 || tail -30 "$LOG" >&2
exit 1
