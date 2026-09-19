#!/bin/sh
# Canned-payload checks for agy-stop-gate.py. Run manually: ./hooks/test-agy-stop-gate.sh
# Tooling, not app code - deliberately no Gradle/JUnit involvement.
set -e
cd "$(dirname "$0")"
GATE=./agy-stop-gate.py
WS=$(mktemp -d)
BRAIN=$(mktemp -d)
export TMPDIR="$(mktemp -d)"
export AGY_GATE_IDLE_SLEEP=0
fails=0

payload() {  # $1=fullyIdle $2=conversationId
  printf '{"conversationId":"%s","fullyIdle":%s,"workspacePaths":["%s"],"artifactDirectoryPath":"%s"}' "$2" "$1" "$WS" "$BRAIN"
}

expect() {  # $1=label $2=expected-decision $3=actual-json
  got=$(printf '%s' "$3" | python3 -c 'import json,sys;print(json.load(sys.stdin)["decision"])')
  if [ "$got" = "$2" ]; then
    echo "ok   - $1"
  else
    echo "FAIL - $1: expected $2, got $got"
    fails=$((fails+1))
  fi
}

expect "no .agy-task.md leaves interactive runs alone" stop "$(payload false c1 | $GATE)"

touch "$WS/.agy-task.md"
expect "busy background task blocks the stop" continue "$(payload false c2 | $GATE)"
expect "idle but no green build blocks the stop" continue "$(payload true c3 | $GATE)"

mkdir -p "$BRAIN/.system_generated/tasks" "$WS/src"
touch "$WS/src/Main.kt"
sleep 1
cat > "$BRAIN/.system_generated/tasks/task-1.log" <<'LOG'
> Task :compileKotlin
> Task :test
> Task :check
> Task :build
BUILD SUCCESSFUL in 56s
LOG
expect "green full build releases the stop" stop "$(payload true c4 | $GATE)"

cat > "$BRAIN/.system_generated/tasks/task-2.log" <<'LOG'
> Task :compileKotlin
> Task :test
BUILD SUCCESSFUL in 15s
LOG
rm "$BRAIN/.system_generated/tasks/task-1.log"
expect "a filtered test run is not a full build" continue "$(payload true c5 | $GATE)"

cat > "$BRAIN/.system_generated/tasks/task-3.log" <<'LOG'
> Task :compileKotlin
> Task :test
> Task :check
> Task :build
BUILD SUCCESSFUL in 56s
LOG
sleep 1
touch "$WS/src/Main.kt"   # edited after the green build
expect "a build older than the last edit does not count" continue "$(payload true c6 | $GATE)"

i=0; while [ $i -le 40 ]; do out=$(payload false c7 | $GATE); i=$((i+1)); done
expect "idle-wait budget is bounded" stop "$out"

i=0; while [ $i -le 3 ]; do out=$(payload true c8 | $GATE); i=$((i+1)); done
expect "build-gate budget is bounded" stop "$out"

expect "malformed stdin fails open" stop "$(printf 'not json' | $GATE)"

rm -rf "$WS" "$BRAIN" "$TMPDIR"
[ "$fails" -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
