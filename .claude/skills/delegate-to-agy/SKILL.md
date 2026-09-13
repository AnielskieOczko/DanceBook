---
name: delegate-to-agy
description: Use when handing a fully-specified GitHub issue to Google Antigravity (agy) for implementation - sets up an isolated worktree, briefs the agent, runs it sandboxed in the background, and validates that the run actually did work. Trigger on "delegate issue N to agy", "have agy implement N", or /delegate-to-agy.
---

# Delegate an issue to Antigravity (`agy`)

Claude does the expensive thinking; `agy` (Gemini 3.8 Flash) does the typing. This skill
covers the handoff only — verification is `verify-agy-work`.

**Why this shape:** every `agy` invocation re-pays ~31k tokens of fixed harness overhead
against a Google AI Pro quota that refreshes every 5h up to a weekly ceiling. So: one run
per issue, never one run per step, and resume conversations instead of starting fresh.

## Non-negotiables

- **Always `--sandbox`.** The user requires it.
- **Never `--dangerously-skip-permissions`.** It auto-approves the agent's own request to
  bypass the sandbox, silently voiding it (antigravity-cli issue #36). The two flags
  together are worse than neither, because they look safe.
- **Never `--mode plan`** headlessly — it blocks forever waiting for an approval prompt
  that cannot appear.
- **Always background the run** (`run_in_background: true`). Real runs take tens of
  minutes; a trivial prompt took 7 minutes.
- **Write `-p='…'` last.** A bare `-p` swallows the next flag as its prompt.
- **Never pass `--effort` alongside an effort-suffixed `--model`.** They conflict and the
  run exits immediately with `status:"ERROR"`.

## Steps

### 1. Preflight

```bash
gh issue view <N> --json number,title,body,labels --jq '{number,title,labels:[.labels[].name],body}'
```

Require both, and stop with a clear message if either is missing:
- the label `ready-for-agent`
- an `## Implementation plan` section in the body

A vague issue produces a bad diff and burns quota. Send it back to planning instead.

### 2. Isolate

```bash
git worktree add ../DanceBook-agy-<N> -b agy/issue-<N> main
```

The worktree is also the sandbox boundary — `--sandbox` confines writes to the workspace.

**Then confirm the permission rules came along:**

```bash
test -f ../DanceBook-agy-<N>/.agents/settings.json \
  || { mkdir -p ../DanceBook-agy-<N>/.agents && cp .agents/settings.json ../DanceBook-agy-<N>/.agents/; }
```

`.agents/` is gitignored except for `settings.json`, so the rules travel with the branch
*once committed*. Until then the worktree starts without them and every tool call is
auto-denied — a failure that looks exactly like a model problem but isn't.

### 3. Brief

Write `.agy-task.md` in the worktree containing: the issue title and number, the goal in
your own words, the `## Implementation plan` steps verbatim, and the definition of done.
Do not restate the rules already in `AGENTS.md` (`agy` reads it automatically) — point at
its *Agent delegation contract* section instead.

These scratch files (`.agy-task.md`, `.agy-run.json`, `.agy-review.md`) are already in
`.gitignore`, so they stay out of the diff.

### 4. Run — background, sandboxed

```bash
cd ../DanceBook-agy-<N> && GRADLE_USER_HOME=$PWD/.gradle-home \
agy --sandbox --model gemini-3.8-flash-high \
    --output-format json --print-timeout 45m \
    -p='Read .agy-task.md and implement it fully, following the Agent delegation contract in AGENTS.md. Run ./gradlew build until it passes. Then summarise what you changed.' \
    > .agy-run.json 2>&1
```

`GRADLE_USER_HOME` must point inside the worktree — Gradle's default `~/.gradle` is
outside the sandbox boundary and the build will fail without it.

Reasoning effort is encoded in the model id, not a separate flag: `gemini-3.8-flash-low`
/ `-medium` / `-high`. **Passing both `--model gemini-3.8-flash-high` and `--effort` is a
hard error** (`conflicts with --effort`) and the run dies before doing anything. Use
`-medium` for mechanical work; keep `-high` for anything with real logic.

### 5. Validate — do not trust `status`

```bash
python3 .claude/skills/delegate-to-agy/validate-run.py ../DanceBook-agy-<N>/.agy-run.json
```

`agy` returns exit 0 and `status:"SUCCESS"` even when it timed out or had every tool
auto-denied. The validator checks what actually matters (non-empty `response`,
`num_turns > 0`, no `denied_actions`) and prints the token spend.

If it reports **auto-denied tools**, add the specific command to `permissions.allow` in
`~/.gemini/antigravity-cli/settings.json`, then resume the *same* conversation rather than
paying the onboarding cost again:

```bash
agy --sandbox --conversation <conversation_id> --output-format json \
    --print-timeout 45m -p='Continue where you left off.' > .agy-run.json 2>&1
```

### 6. Report

Give the user: tokens and wall-clock from the validator, `git -C ../DanceBook-agy-<N> diff --stat`,
and agy's own summary. Then hand off to `verify-agy-work`.

Record the run's `conversation_id` — Phase 2 needs it for fix rounds.
