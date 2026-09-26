#!/usr/bin/env bash
# Progress of delegated agy runs, read from files on disk only - no model is called, so it
# costs no Claude or Gemini usage and is cheap enough for a status line.
#
#   agy-status.sh           one block per clone (../DanceBook-agy-<N>)
#   agy-status.sh 154       just that issue
#   agy-status.sh --line    one short line per active or recently finished run, for the
#                           Claude Code status line (prints nothing when agy is idle)
#   watch -n 10 .claude/skills/delegate-to-agy/agy-status.sh
#
# Sources: the clone (.agy-start, .agy-plan.md checklist, git diff, .agy-run.json), the
# running agy process, and agy's conversation dir (steps/ and tasks/*.log).
set -uo pipefail

LIMIT_MIN=45                 # --print-timeout used by delegate-to-agy
RECENT_SECS=1800             # keep a finished run in --line output for 30 min
BRAIN=~/.gemini/antigravity-cli/brain
MODE=full; ONLY=""
for a in "$@"; do case $a in --line) MODE=line ;; *) ONLY=$a ;; esac; done

REPO=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || exit 0
PARENT=$(dirname "$REPO")
now=$(date +%s)
mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0; }
ago() { local s=$1; if [ "$s" -lt 60 ]; then echo "${s}s"; elif [ "$s" -lt 3600 ]; then echo "$((s/60))m"; else echo "$((s/3600))h$(( (s%3600)/60 ))m"; fi; }
bar() { local done=$1 total=$2 width=10 filled; filled=$(( total > 0 ? done * width / total : 0 )); [ $filled -gt $width ] && filled=$width
  printf '%s%s' "$(printf '▓%.0s' $(seq 1 $filled) 2>/dev/null | head -c $((filled*3)))" "$(printf '░%.0s' $(seq 1 $((width-filled))) 2>/dev/null)"; }

# Which agy conversation belongs to a clone. Cached in .agy-conversation once found.
conversation_for() {
  local clone=$1 pid_args=$2 id=""
  id=$(sed -n 's/.*--conversation \([0-9a-f-]\{36\}\).*/\1/p' <<<"$pid_args" | head -1)
  if [ -z "$id" ] && [ -s "$clone/.agy-conversation" ] && [ "$(mtime "$clone/.agy-conversation")" -ge "$(mtime "$clone/.agy-start")" ]; then
    id=$(cat "$clone/.agy-conversation")
  fi
  if [ -z "$id" ] && [ -s "$clone/.agy-run.json" ]; then
    id=$(sed -n 's/.*"conversation_id": *"\([0-9a-f-]\{36\}\)".*/\1/p' "$clone/.agy-run.json" | head -1)
  fi
  if [ -z "$id" ]; then   # new run: the conversation whose transcript names this clone
    local start d; start=$(mtime "$clone/.agy-start")
    for d in $(ls -t "$BRAIN" 2>/dev/null | head -5); do
      [ "$(mtime "$BRAIN/$d")" -ge "$((start - 60))" ] || continue
      if grep -qsm1 -F "$clone" "$BRAIN/$d/.system_generated/logs/transcript.jsonl"; then id=$d; break; fi
    done
  fi
  [ -n "$id" ] && echo "$id" > "$clone/.agy-conversation"
  echo "$id"
}

status_of() {
  local clone=$1 n=${1##*-} pid_args running=0 start elapsed conv sys steps last_step idle
  pid_args=$(pgrep -fl "agy --add-dir $clone( |$)" | head -1)
  [ -n "$pid_args" ] && running=1
  start=$(mtime "$clone/.agy-start"); [ "$start" -eq 0 ] && start=$(mtime "$clone/.agy-task.md")
  elapsed=$(( now - start ))

  # Finished and not recent: nothing for the status line.
  if [ $running -eq 0 ] && [ "$MODE" = line ] && [ $(( now - $(mtime "$clone/.agy-run.json") )) -gt $RECENT_SECS ]; then return; fi

  conv=$(conversation_for "$clone" "$pid_args"); sys="$BRAIN/$conv/.system_generated"
  steps=0; idle=""
  if [ -n "$conv" ] && [ -d "$sys/steps" ]; then
    last_step=$(ls "$sys/steps" | sort -n | tail -1); steps=${last_step:-0}
    [ -n "$last_step" ] && idle=$(ago $(( now - $(mtime "$sys/steps/$last_step") )))
  fi

  # Plan checklist: the real progress measure, when agy writes one.
  local total=0 ticked=0 progress
  if [ -f "$clone/.agy-plan.md" ]; then
    total=$(grep -cE '^\s*[-*] \[[ xX]\]' "$clone/.agy-plan.md"); ticked=$(grep -cE '^\s*[-*] \[[xX]\]' "$clone/.agy-plan.md")
  fi
  if [ "$total" -gt 0 ]; then progress="$(bar "$ticked" "$total") $ticked/$total plan"
  elif [ -f "$clone/.agy-plan.md" ]; then progress="$(bar $((elapsed/60)) $LIMIT_MIN) plan written, no checklist"
  else progress="$(bar 0 1) orienting, no plan yet"; fi

  # Gradle runs in this conversation. A full `./gradlew build` is recognised the way the
  # Stop gate does it (:build, :check and :test tasks in one log); filtered test runs and
  # compiles only count as "gradle".
  local builds=0 full_builds=0 last_any="none" last_full="none" f r
  if [ -d "$sys/tasks" ]; then
    for f in $(ls -tr "$sys/tasks"/task-*.log 2>/dev/null); do
      grep -qE 'BUILD (SUCCESSFUL|FAILED)' "$f" || continue
      grep -q 'BUILD SUCCESSFUL' "$f" && r="✓" || r="✗"
      builds=$((builds+1)); last_any=$r
      if grep -q '> Task :build' "$f" && grep -q '> Task :check' "$f" && grep -q '> Task :test' "$f"; then
        full_builds=$((full_builds+1)); last_full=$r
      fi
    done
  fi

  local diff files; files=$(git -C "$clone" status --porcelain 2>/dev/null | grep -vc ' \.agy-')
  local st; st=$(git -C "$clone" diff --shortstat 2>/dev/null)
  diff="+$(grep -oE '[0-9]+ insertion' <<<"$st" | grep -oE '[0-9]+' || echo 0) −$(grep -oE '[0-9]+ deletion' <<<"$st" | grep -oE '[0-9]+' || echo 0) lines in tracked files"

  local state
  if [ $running -eq 1 ]; then state="$(ago $elapsed)/${LIMIT_MIN}m"
  else state="finished $(ago $(( now - $(mtime "$clone/.agy-run.json") ))) ago"; fi

  if [ "$MODE" = line ]; then
    printf 'agy #%s %s · %s · step %s%s · %s files · build %s · gradle %s\n' "$n" "$progress" "$state" "$steps" \
      "${idle:+ ($idle ago)}" "$files" "$last_full" "$last_any"
  else
    echo "agy #$n  ($clone)"
    echo "  progress   $progress"
    echo "  state      $([ $running -eq 1 ] && echo running || echo stopped), $state"
    echo "  activity   step $steps${idle:+, last $idle ago}${conv:+  (conversation $conv)}"
    echo "  changes    $files files  ${diff}"
    echo "  full build $full_builds runs, last $last_full    (any gradle: $builds runs, last $last_any)"
  fi
}

shopt -s nullglob
for clone in "$PARENT"/DanceBook-agy-*; do
  [ -d "$clone/.git" ] || continue
  [ -n "$ONLY" ] && [ "${clone##*-}" != "$ONLY" ] && continue
  status_of "$clone"
done
