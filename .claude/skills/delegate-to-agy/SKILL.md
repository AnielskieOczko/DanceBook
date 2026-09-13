---
name: delegate-to-agy
description: Use when handing a fully-specified GitHub issue to Google Antigravity (agy) for implementation - sets up an isolated worktree, briefs the agent, runs it sandboxed in the background, and validates that the run actually did work. Trigger on "delegate issue N to agy", "have agy implement N", or /delegate-to-agy.
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
- **Always `--sandbox`.** The user requires it. Verified to block writes to `$HOME` and to
  sibling directories (`operation not permitted`) while leaving the workspace, `~/.gradle`
  and `/tmp` writable. `/tmp` is allowed by the macOS profile, so never use it as a canary.
- **Never `--dangerously-skip-permissions`.** agy escalates some operations by requesting
  the `unsandboxed` permission; headless mode auto-denies it, and that denial *is* the
  sandbox guarantee. This flag auto-approves it (antigravity-cli#36), silently voiding the
  sandbox.
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
"permissions": { "allow": ["command(*)", "write_file(*)", "read_file(*)"], "deny": [] }
```

Headless mode cannot prompt, so anything not allowed here is auto-denied and the run does
nothing. These rules are broad on purpose — **`--sandbox` is the control, not the
allowlist.** Note this is global scope: it also suppresses prompting for non-sandboxed agy
sessions elsewhere. Project-scoped permissions were tried in `.agents/settings.json` and
are *not* read by the CLI.

The worktree's parent must be in `trustedWorkspaces` (`/Volumes/my-data/Developer/Projects`
covers every `../DanceBook-agy-<N>`).

Keep `agy mcp list` clean. A dead MCP server blocks startup for minutes on *every* run —
removing one took a trivial run from 430s to 10s.

## Steps

### 1. Preflight

```bash
gh issue view <N> --json number,title,body,labels --jq '{number,title,labels:[.labels[].name],body}'
```

Require the `ready-for-agent` label and an `## Implementation plan` section, and stop if
either is missing. A vague issue produces a bad diff and burns quota.

### 2. Isolate — a clone, not a worktree

```bash
git clone -q . ../DanceBook-agy-<N>
git -C ../DanceBook-agy-<N> checkout -q -b agy/issue-<N> origin/main
```

**Do not use `git worktree`.** A worktree's `.git` is a *file* pointing into the main
repo's `.git/worktrees/…`, which lives outside the sandboxed workspace; reaching it makes
agy escalate to `unsandboxed`, which headless auto-denies, and the run dies having written
nothing. A clone's `.git` is a real directory inside the workspace, and works.

Cleanup is `rm -rf ../DanceBook-agy-<N>` — no `git worktree remove`. (If a worktree was
ever registered at that path, `git worktree prune` will not clear it while the directory
exists; delete `.git/worktrees/DanceBook-agy-<N>` by hand.)

### 3. Brief

Write `.agy-task.md` in the worktree: issue title and number, the goal in your own words,
the `## Implementation plan` steps verbatim, and the definition of done. Don't restate the
rules in `AGENTS.md` (agy reads it) — point at its *Agent delegation contract* section.

Tell it explicitly **not to use git**: agy requests `unsandboxed` for git operations, which
headless auto-denies. Claude owns every git operation in this workflow.

These scratch files are already in `.gitignore`, so they stay out of the diff.

### 4. Run — background, sandboxed, attached

```bash
cd ../DanceBook-agy-<N> && agy --sandbox --add-dir "$PWD" \
    --model gemini-3.8-flash-high --output-format json --print-timeout 45m \
    -p='Read .agy-task.md and implement it fully, following the Agent delegation contract in AGENTS.md. Do not run the full build. Then summarise what you changed.' \
    > .agy-run.json 2>&1
```

Use `-medium` for mechanical work, `-high` for real logic.

**agy cannot run this project's full `./gradlew build` while sandboxed.** `build` runs the
`@Testcontainers` integration tests, which need the Docker socket — outside the sandbox by
definition — so the run escalates to `unsandboxed` and is denied. No permission rule fixes
this without granting the very escape the sandbox exists to prevent.

So ask agy for the code, not the build: drop "run ./gradlew build" from the prompt and let
`verify-agy-work` build it. Narrow unit-test-only commands such as
`./gradlew test --tests "*FooTest*"` may work, but treat that as unproven per project.
The consequence is a slower fix loop — build errors round-trip through Claude instead of
agy self-correcting — which is the price of the sandbox requirement.

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
agy --sandbox --add-dir "$PWD" --conversation <conversation_id> --output-format json \
    --print-timeout 45m -p='Continue where you left off.' > .agy-run.json 2>&1
```

### 6. Report

Give the user tokens and wall-clock from the validator, `git -C ../DanceBook-agy-<N> status --short` (agy makes no commits, so its work shows as untracked/modified files),
and agy's summary. **Confirm the diff is non-empty** — an empty diff with a cheerful
summary means `--add-dir` was missing or ineffective. Then hand off to `verify-agy-work`,
and keep the `conversation_id` for fix rounds.
