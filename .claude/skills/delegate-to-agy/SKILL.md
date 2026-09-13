---
name: delegate-to-agy
description: Use when handing a fully-specified GitHub issue to Google Antigravity (agy) for implementation - sets up a disposable clone, briefs the agent, runs it in the background, and validates that the run actually did work. Trigger on "delegate issue N to agy", "have agy implement N", or /delegate-to-agy.
---

# Delegate an issue to Antigravity (`agy`)

Claude does the expensive thinking; `agy` (Gemini 3.8 Flash) does the typing. This skill
covers the handoff only — verification is `verify-agy-work`.

Every flag and rule below was established by probing the real CLI. Changing any of them
should also be done by probing, not by reasoning about what ought to work.

## Non-negotiables

- **Always `--add-dir <worktree-abs-path>`.** Without it agy never attaches to the project:
  it silently works inside `~/.gemini/antigravity-cli/scratch`, writes files there, and
  reports success. This is the single worst failure mode — a green run, an empty diff.
- **No `--sandbox`.** It was required at first and deliberately dropped: under it, agy
  cannot run any real Gradle build (the daemon fork and dependency resolution escalate to
  the `unsandboxed` permission, which headless auto-denies), so it can write code but
  never verify it. That forces every compile error through Claude and destroys the saving
  delegation exists for. **The blast radius is the disposable clone**, not an OS boundary.
- **Never `--dangerously-skip-permissions`.** Keep grants explicit in `permissions.allow`
  so the deny list still applies.
- **Never `--mode plan`** headlessly — it blocks forever waiting for an approval prompt
  that cannot appear.
- **Never pass `--effort` alongside an effort-suffixed `--model`.** They conflict and the
  run exits immediately with `status:"ERROR"`. Effort lives in the model id:
  `gemini-3.8-flash-low` / `-medium` / `-high`.
- **Write `-p='…'` last.** A bare `-p` swallows the next flag as its prompt.
- **Background the run** (`run_in_background: true`) for anything real.

## Prerequisites (once)

`~/.gemini/antigravity-cli/settings.json` needs:

```json
"permissions": {
  "allow": ["command(*)", "read_file(*)", "write_file(/Volumes/my-data/Developer/Projects/DanceBook-agy-*/)"],
  "deny": ["command(rm -rf /*)", "command(sudo *)", "command(gh *)", "command(git push *)"]
}
```

Headless mode cannot prompt, so anything not allowed is auto-denied and the run does
nothing. Path-scoped `write_file` is genuinely enforced (verified: a write to `$HOME` was
refused). **`command(*)` is the hole** — agy can shell-write anywhere the user can, so
treat these rules as a speed bump, not containment. Narrowing commands helps little:
Gradle runs arbitrary build code and git runs hooks.

**This file is global to the machine**, so these rules apply to every agy session in every
project, not just DanceBook. Project-scoped permissions were tried in `.agents/settings.json`
and are *not* read; agy's real project scope is keyed to its own `--project` entity.

The worktree's parent must be in `trustedWorkspaces` (`/Volumes/my-data/Developer/Projects`
covers every `../DanceBook-agy-<N>`).

Keep `agy mcp list` clean. A dead MCP server blocks startup for minutes on *every* run —
removing one took a trivial run from 430s to 10s.

## Steps

### 1. Preflight

```bash
gh issue view <N> --json number,title,body,labels --jq '{number,title,labels:[.labels[].name],body}'
python3 .claude/skills/delegate-to-agy/usage.py
```

The `/usage` slash command works headlessly and **costs zero tokens**. Record the Gemini
weekly and 5-hour remaining fractions before and after the run — the difference is the
real price of the issue, and the only honest input to "can Pro sustain this".

Require the `ready-for-agent` label and an `## Implementation plan` section, and stop if
either is missing. A vague issue produces a bad diff and burns quota.

### 2. Isolate — a clone, not a worktree

```bash
git clone -q . ../DanceBook-agy-<N>
git -C ../DanceBook-agy-<N> checkout -q -b agy/issue-<N> origin/main
```

**A clone, not a `git worktree`.** The clone *is* the safety model now that the sandbox is
gone: agy works on a throwaway copy, so a mistake cannot corrupt the real repo's history or
your working tree, and nothing reaches `main` without review. A worktree would share
`.git` with the main repo and give up exactly that protection.

Cleanup is `rm -rf ../DanceBook-agy-<N>` — no `git worktree remove`. (If a worktree was
ever registered at that path, `git worktree prune` will not clear it while the directory
exists; delete `.git/worktrees/DanceBook-agy-<N>` by hand.)

### 3. Brief

Write `.agy-task.md` in the worktree: issue title and number, the goal in your own words,
the `## Implementation plan` steps verbatim, and the definition of done. Don't restate the
rules in `AGENTS.md` (agy reads it) — point at its *Agent delegation contract* section.

Tell it **not to commit, push, or open PRs** — Claude owns version control, and
`command(gh *)` and `command(git push *)` are denied anyway. Read-only git (`status`,
`diff`, `log`) is fine and useful to it.

These scratch files are already in `.gitignore`, so they stay out of the diff.

### 4. Run — background, attached

```bash
cd ../DanceBook-agy-<N> && agy --add-dir "$PWD" \
    --model gemini-3.8-flash-high --output-format json --print-timeout 45m \
    -p='Read .agy-task.md and implement it fully, following the Agent delegation contract in AGENTS.md. Run ./gradlew build and fix any failures yourself until it passes. Then summarise what you changed.' \
    > .agy-run.json 2>&1
```

Use `-medium` for mechanical work, `-high` for real logic.

**Insist that agy runs `./gradlew build` and fixes its own failures until green.** This is
the whole point: every compile error it resolves itself is a Claude round-trip that never
happens. Verifying its work afterwards is still mandatory — it is a second opinion, not
the first run.

### 5. Validate — do not trust `status`

```bash
python3 .claude/skills/delegate-to-agy/validate-run.py ../DanceBook-agy-<N>/.agy-run.json
```

`agy` returns exit 0 and `status:"SUCCESS"` for timeouts, flag errors and fully-denied
runs alike. The validator checks non-empty `response`, `num_turns > 0` and absent
`denied_actions`, and prints the token spend.

If it reports denied tools, add the action to `permissions.allow`, then resume the *same*
conversation instead of re-paying the onboarding cost:

```bash
agy --add-dir "$PWD" --conversation <conversation_id> --output-format json \
    --print-timeout 45m -p='Continue where you left off.' > .agy-run.json 2>&1
```

### 6. Report

Give the user tokens and wall-clock from the validator, `git -C ../DanceBook-agy-<N> status --short` (agy makes no commits, so its work shows as untracked/modified files),
and agy's summary. **Confirm the diff is non-empty** — an empty diff with a cheerful
summary means `--add-dir` was missing or ineffective. Then hand off to `verify-agy-work`,
and keep the `conversation_id` for fix rounds.
