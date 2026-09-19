#!/usr/bin/env python3
"""Stop-hook gate: stop agy from ending a delegated run before its build is in.

agy's `run_command` caps `WaitMsBeforeAsync` at 10000ms, so every Gradle build is
detached into a background task. The result comes back as a task notification -
but only if the turn is still alive when it arrives, and headless runs lose that
race (issue #84: 14 build launches, one notification, no full build ever seen).

This hook is the fix agy itself provides. It refuses to let the loop terminate
while a background task is still running, and then refuses again until a full
`./gradlew build` has actually gone green in this workspace.

It engages only when `.agy-task.md` is present, so interactive sessions in this
repo are untouched. Every failure path falls open to "stop": a hook that crashes
must never be able to wedge a run.
"""
import json
import os
import sys
import time

IDLE_SLEEP_SECONDS = int(os.environ.get("AGY_GATE_IDLE_SLEEP", "25"))  # hooks block the loop; this paces retries
MAX_IDLE_CONTINUES = 40      # ~17 min of build wall-clock
MAX_BUILD_CONTINUES = 3      # fix rounds before we give up and let it stop
DEADLINE_SECONDS = 3600      # absolute ceiling from the first gate invocation

STOP = {"decision": "stop"}

# A full `./gradlew build` runs all three. A filtered `test --tests ...` run has
# :test but neither :build nor :check, and `assemble`/`testClasses` has neither.
FULL_BUILD_MARKERS = ("> Task :build", "> Task :check", "> Task :test")


def emit(payload):
    print(json.dumps(payload))
    sys.exit(0)


def state_path(conversation_id):
    base = os.path.join(os.environ.get("TMPDIR", "/tmp"), "agy-stop-gate")
    os.makedirs(base, exist_ok=True)
    safe = "".join(c for c in conversation_id if c.isalnum() or c in "-_") or "unknown"
    return os.path.join(base, safe + ".json")


def load_state(path):
    try:
        with open(path) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {"idle_continues": 0, "build_continues": 0, "started": time.time(), "log": []}


def save_state(path, state):
    try:
        with open(path, "w") as fh:
            json.dump(state, fh)
    except OSError:
        pass


def newest_source_mtime(workspace):
    """Newest mtime under src/, so a build from before the last edit cannot count."""
    newest = 0.0
    src = os.path.join(workspace, "src")
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for name in files:
            try:
                newest = max(newest, os.stat(os.path.join(root, name)).st_mtime)
            except OSError:
                continue
    return newest


def green_full_build(brain_dir, workspace):
    """Return the `BUILD SUCCESSFUL in ...` line of a full build newer than the last edit."""
    tasks = os.path.join(brain_dir, ".system_generated", "tasks")
    if not os.path.isdir(tasks):
        return None
    cutoff = newest_source_mtime(workspace)
    best = None
    for name in os.listdir(tasks):
        if not name.endswith(".log"):
            continue
        path = os.path.join(tasks, name)
        try:
            stat = os.stat(path)
            if stat.st_mtime < cutoff:
                continue
            with open(path, errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        if not all(marker in text for marker in FULL_BUILD_MARKERS):
            continue
        for line in reversed(text.splitlines()):
            if line.startswith("BUILD SUCCESSFUL"):
                if best is None or stat.st_mtime > best[0]:
                    best = (stat.st_mtime, line.strip())
                break
            if line.startswith("BUILD FAILED"):
                break
    return best[1] if best else None


def main():
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except ValueError:
        emit(STOP)

    workspaces = payload.get("workspacePaths") or []
    workspace = workspaces[0] if workspaces else os.getcwd()

    # Not a delegated run - never interfere with an interactive session.
    if not os.path.exists(os.path.join(workspace, ".agy-task.md")):
        emit(STOP)

    conversation_id = payload.get("conversationId") or ""
    path = state_path(conversation_id)
    state = load_state(path)

    if time.time() - state.get("started", time.time()) > DEADLINE_SECONDS:
        state["log"].append("deadline exceeded - released")
        save_state(path, state)
        emit(STOP)

    # 1. A background task is still running. Almost always ./gradlew build.
    if not payload.get("fullyIdle", True):
        if state["idle_continues"] >= MAX_IDLE_CONTINUES:
            state["log"].append("idle-wait budget exhausted - released")
            save_state(path, state)
            emit(STOP)
        state["idle_continues"] += 1
        save_state(path, state)
        time.sleep(IDLE_SLEEP_SECONDS)
        emit({
            "decision": "continue",
            "reason": (
                "A background task is still running - almost certainly ./gradlew build. "
                "Do not finish and do not launch another build. Wait for the completion "
                "notification and read its result."
            ),
        })

    if os.environ.get("AGY_GATE_SKIP_BUILD"):
        emit(STOP)

    # 2. Idle, but has a full build actually gone green since the last edit?
    brain = payload.get("artifactDirectoryPath") or ""
    if green_full_build(brain, workspace):
        emit(STOP)

    if state["build_continues"] >= MAX_BUILD_CONTINUES:
        state["log"].append("build-gate budget exhausted - released without a green build")
        save_state(path, state)
        emit(STOP)
    state["build_continues"] += 1
    save_state(path, state)
    emit({
        "decision": "continue",
        "reason": (
            "You have not completed a full `./gradlew build` in this workspace since your "
            "last edit. Run `./gradlew build` now. It will be sent to the background - that "
            "is normal and expected. Wait for its completion notification, fix any failures "
            "yourself, and re-run it until it passes before you finish."
        ),
    })


if __name__ == "__main__":
    try:
        main()
    except Exception:  # fail open - never wedge a run
        print(json.dumps(STOP))
