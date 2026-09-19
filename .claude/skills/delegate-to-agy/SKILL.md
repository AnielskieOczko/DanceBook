---
name: delegate-to-agy
description: Use when handing implementation work to Google Antigravity (agy) - writes a short specification, opens it as a GitHub issue, sets up a disposable clone, runs the agent in the background, and publishes the plan it wrote back to the issue. Trigger on "delegate issue N to agy", "have agy implement N", or /delegate-to-agy.
---

# Delegate an issue to Antigravity (`agy`)

Claude writes the **specification**; `agy` (Gemini 3.8 Flash) works out the implementation
and writes the code. This skill covers spec-writing and the handoff — verification is
`verify-agy-work`.

**The division of labour is the point.** Exploring the codebase to decide which files to
touch is the expensive part, and it now belongs to agy, on the Google subscription. A spec
that already names the files and the edits has moved that cost back onto Claude and left
agy transcribing — which is the failure this workflow was rebuilt to stop.

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
  that cannot appear. agy plans inside a normal run instead, by writing `.agy-plan.md`.
- **Never pass `--effort` alongside an effort-suffixed `--model`.** They conflict and the
  run exits immediately with `status:"ERROR"`. Effort lives in the model id:
  `gemini-3.8-flash-low` / `-medium` / `-high`.
- **Write `-p='…'` last.** A bare `-p` swallows the next flag as its prompt.
- **Background the run** (`run_in_background: true`) for anything real.
- **Do not ask for a foreground build — agy cannot run one.** `run_command` takes a
  `WaitMsBeforeAsync`, and its own schema caps it at **10000ms**; anything still running
  after that is detached into a background task. Every Gradle build here takes 14–56s, so
  every build is backgrounded, always. The cap is server-side: a probe passing `85000` was
  backgrounded anyway. Upstream issue #983 confirms the 10-second threshold.
- **Backgrounding is the working path, not the failure.** The full output comes back as a
  `task_notification`, and each task also leaves a durable log at
  `~/.gemini/antigravity-cli/brain/<conversation_id>/.system_generated/tasks/task-<N>.log`.
  That log — not any stderr line — is the evidence that a build ran.
- **The failure is the turn ending while the task is still running.** agy replies "I will
  report the output once it finishes", the run ends, and the result never arrives. It is a
  race, so no prompt wording fixes it. The `Stop` hook below does.

## Prerequisites (once)

`~/.gemini/antigravity-cli/settings.json` needs:

```json
"permissions": {
  "allow": ["command(*)", "read_file(*)", "write_file(./)"],
  "deny": ["command(rm -rf /*)", "command(sudo *)", "command(gh *)", "command(git push *)"]
}
```

Headless mode cannot prompt, so anything not allowed is auto-denied and the run does
nothing.

`write_file(./)` is deliberately **relative**: agy resolves relative permission paths
against the repository root of the attached workspace, so one rule covers every clone on
every machine — no absolute paths to edit. Verified: it permits nested writes like
`src/test/kotlin/.../Foo.kt` and refuses `$HOME`.

**Keep `command(gh *)` denied.** agy's plan reaches the issue because Claude posts the
file it wrote (step 6), not because agy has tracker access. Granting `gh` would give every
agy session on this machine the ability to close issues and edit pull requests, to save a
single `gh issue comment` that costs Claude nothing.

**`command(*)` is the hole.** agy can shell-write anywhere the user can, which defeats the
`write_file` scope, so treat these rules as a speed bump rather than containment. Narrowing
commands helps little: Gradle runs arbitrary build code and git runs hooks. The settings
that look like they would fix this — `outsideWorkspaceFileAccessPolicy: "deny"` and
`disableToolCallExecutionOutsideWorkspace: true` — were tested and had **no observable
effect**; don't set them and assume you are protected. The real control remains the
disposable clone plus two reviews.

**This file is global to the machine**, so these rules apply to every agy session in every
project, not just DanceBook. Project-scoped permissions were tried in `.agents/settings.json`
and are *not* read; agy's real project scope is keyed to its own `--project` entity.

The worktree's parent must be in `trustedWorkspaces` (`/Volumes/my-data/Developer/Projects`
covers every `../DanceBook-agy-<N>`).

`.agents/hooks.json` registers a `Stop` hook, `.agents/hooks/agy-stop-gate.py`, which is
what actually makes agy verify its own work. agy's `Stop` hook receives `fullyIdle` ("true
if all background tasks are done") and can return `{"decision":"continue"}` to block
termination and re-enter the loop. The gate refuses to stop while a background task is
running, and then refuses again until a full `./gradlew build` has gone green *since the
last edit* — identified by `> Task :build`, `:check` and `:test` together in a task log, so
a filtered `test --tests …` run cannot satisfy it. It engages only when `.agy-task.md` is
present, so your own interactive agy sessions in this repo are untouched. Continues are
bounded (40 idle waits, 3 build rounds, a 1h deadline) and every error path falls open to
`stop`, so the gate can never wedge a run.

**These three files are the only tracked content under `.agents/`** — the rest of that
directory is still gitignored. They have to be tracked because delegation works on a
`git clone`, and an untracked hook would not reach it. **A clone made before this change
has no gate**; re-clone rather than reasoning about why a run stopped early.

Verified by probe: with the gate, `sleep 75 && echo PROBE_MARKER_DONE` returns its output
after 90s; with `.agy-task.md` removed so the gate stands down, the identical run ends in
6s having lost the result. Run `.agents/hooks/test-agy-stop-gate.sh` after editing the gate.

Keep `agy mcp list` clean. A dead MCP server blocks startup for minutes on *every* run —
removing one took a trivial run from 430s to 10s.

## Steps

### 0. Write the specification

Skip only if the issue already exists and is spec-shaped.

A spec says **what must be true when the work is done**, and why. It does not say which
files to edit. Sections:

```markdown
## Why
The problem, as a user-visible symptom. What is wrong or missing today.

## What to build
The behaviour. What someone can do afterwards that they cannot do now.

## Acceptance criteria
- [ ] Statements a reviewer can tick by using the app or reading a test name.

## Out of scope
The adjacent things deliberately not in this issue, so agy does not wander into them.

## Decisions for the implementer
Ambiguities you already know about, with the constraint that bounds each one — agy picks
a reading and records it. Not answers; guardrails.
```

Aim for ~40 lines.

**Budget your own exploration.** One targeted look at an unfamiliar area is fine. Tracing
call paths, opening every collaborator, and collecting line numbers is not — that is the
scan being moved to Gemini, and doing it anyway defeats the delegation even if the spec
you write from it *looks* restrained.

**Reject your own draft** if it contains any of:

- a code block, or the signature of a method you want created
- a line number, or a `File.kt L146`-style reference
- a file-by-file list of edits
- a `### Step 1 / 2 / 3` breakdown of the implementation
- the tests you want written, spelled out as assertions

The test is whether agy could reasonably choose differently after reading the code. If it
could, you have described an outcome; if it could not, you have written the implementation.

**Naming existing code is not prescribing an implementation.** A bug report has to say
what is broken, and a coverage issue has to say what is untested — naming the method or
the page whose behaviour must hold is the *subject* of the criterion, not a direction to
edit that file. The line is between "this branch must be covered" (a spec) and "add
`findAllByEnabledTrue()` to the repository" (a plan). Naming one or two landmark files so
agy starts in the right neighbourhood is fine for the same reason.

**Issue #58 is the worked example of what not to write**: 48 lines that specified the
implementation down to `form.html` L146 and the exact repository method to add. Everything
agy did on it, Claude had already done.

Write the spec to `.agy-spec.md` in the main repo, then:

```bash
gh issue create --title "..." --body-file .agy-spec.md --label ready-for-agent
```

### 1. Preflight

```bash
gh issue view <N> --json number,title,body,labels --jq '{number,title,labels:[.labels[].name],body}'
python3 .claude/skills/delegate-to-agy/usage.py
```

Require the `ready-for-agent` label and acceptance criteria, and stop if either is
missing. Also stop if the body *prescribes an implementation* — an inherited issue written
under the old workflow should be rewritten as a spec first, not handed over as-is.

The `/usage` slash command works headlessly and **costs zero tokens**. Record the Gemini
weekly and 5-hour remaining fractions before and after the run — the difference is the
real price of the issue, and the only honest input to "can Pro sustain this".

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

### 3. Brief — a pointer, not a restatement

`.agy-task.md` is the issue and nothing more. Build it without reading it into context:

```bash
gh issue view <N> --json number,title,body \
  --jq '"# Issue #\(.number): \(.title)\n\n\(.body)"' > ../DanceBook-agy-<N>/.agy-task.md
```

Then append two lines by hand: follow the *Agent delegation contract* in `AGENTS.md`, and
version control is handled outside the session. Nothing else — no plan, no file list, no
restatement of rules agy already reads in `AGENTS.md`.

These scratch files are already in `.gitignore`, so they stay out of the diff.

### 4. Run — background, attached

```bash
cd ../DanceBook-agy-<N> && agy --add-dir "$PWD" \
    --model gemini-3.8-flash-high --output-format json --print-timeout 45m \
    -p='Read .agy-task.md. Follow the Agent delegation contract in AGENTS.md: orient yourself in the codebase, write .agy-plan.md before you edit anything, then implement it fully with tests. Then run ./gradlew build. The runtime will send it to the background - that is normal and expected, so do not relaunch it. Wait for its completion notification, fix any failures yourself, and re-run it until it passes. Your final message must end by quoting the last two lines of that build verbatim; if you cannot quote them, you are not finished.' \
    > .agy-run.json 2>&1
```

**Default to `-high`.** agy is doing the thinking now, not the typing; `-medium` is for
genuinely mechanical issues where the shape of the change is not in question.

**The prompt demands a verbatim build result, and the `Stop` gate enforces it.** Asking for
a quote gives the model a finish condition it cannot satisfy by stopping early; the gate
makes stopping early impossible. Together they keep compile errors on Gemini's side of the
line, which is the whole point — every one agy fixes itself is a Claude round-trip that
never happens. Verifying afterwards is still mandatory: a second opinion, not the first run.

Issue #84 is the worked example of the cost when this goes wrong: two runs, ~6 hours of wall
clock and 4.8M tokens, and the delivered tests did not compile — a single `!` where `!!` was
meant, which a real build would have caught in 20 seconds. Its transcript shows 14
`./gradlew build` launches, exactly **one** task notification, and the only
`BUILD SUCCESSFUL` anywhere in it was a 17s `compileKotlin`. It ran on agy 1.2.6, whose
changelog entry for 1.2.7 reads "Fixed headless (`-p`) runs occasionally skipping the
background-task waiting notice" — which is why issue #85 on 1.2.7 did receive its
`BUILD SUCCESSFUL in 56s` and quote it. *Occasionally* is the problem the gate removes.

### 5. Validate — do not trust `status`

```bash
python3 .claude/skills/delegate-to-agy/validate-run.py ../DanceBook-agy-<N>/.agy-run.json
test -s ../DanceBook-agy-<N>/.agy-plan.md || echo "NO PLAN - agy skipped the work loop"
```

`agy` returns exit 0 and `status:"SUCCESS"` for timeouts, flag errors and fully-denied
runs alike. The validator checks non-empty `response`, `num_turns > 0` and absent
`denied_actions`, and prints the token spend.

**A missing `.agy-plan.md` is a red flag even when the diff looks plausible.** It means agy
did not follow the work loop, so nothing else it was told to do is safe to assume either.

The validator also checks the conversation's task logs for a green full `./gradlew build`
and prints the `BUILD SUCCESSFUL in …` line it found. That log is written by the runtime,
so unlike agy's summary it cannot be claimed — but it says the build passed *somewhere in
that run*, not that the final diff is green.

**So always run `./gradlew build` yourself before reviewing.** agy claiming green is not
evidence, and `status:"ERROR"` with a substantial diff is common — the work can be most of
the way there while never having compiled once. Pipe gradle through `tail` only with
`set -o pipefail`, or you will read `tail`'s exit code and call a failed build a pass.

If it reports denied tools, add the action to `permissions.allow`, then resume the *same*
conversation instead of re-paying the onboarding cost:

```bash
agy --add-dir "$PWD" --conversation <conversation_id> --output-format json \
    --print-timeout 45m -p='Continue where you left off.' > .agy-run.json 2>&1
```

### 6. Publish agy's plan to the issue

```bash
gh issue comment <N> --body-file ../DanceBook-agy-<N>/.agy-plan.md
```

**Do not read the plan into context here.** `--body-file` moves it at zero token cost, and
it gets read exactly once — during verification, where the judgement happens. Posting it
gives the human reviewer agy's reasoning next to the diff, and leaves a record on the issue
of how the spec was interpreted.

### 7. Report

Give the user tokens and wall-clock from the validator, `git -C ../DanceBook-agy-<N> status --short` (agy makes no commits, so its work shows as untracked/modified files),
the link to the plan comment, and agy's summary. **Confirm the diff is non-empty** — an
empty diff with a cheerful summary means `--add-dir` was missing or ineffective. Then hand
off to `verify-agy-work`, and keep the `conversation_id` for fix rounds.
