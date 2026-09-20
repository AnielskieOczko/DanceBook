---
name: verify-agy-work
description: Use after Antigravity (agy) has implemented an issue, to check its plan against the spec, review the diff, independently confirm the build, request changes, and open the PR. Trigger on "verify agy's work", "review the agy branch", or /verify-agy-work.
---

# Verify agy's work and ship it

Claude's job here is the part Gemini Flash should not be trusted with: judging whether the
work is *right for this codebase and this spec*, not merely whether it compiles.

Since agy now plans its own implementation, there are two things to judge and they fail
differently — a misread spec, and a bad diff. Check them in that order; the first is
cheaper and makes the second moot.

## 1. Read agy's plan against the spec

```bash
cat ../DanceBook-agy-<N>/.agy-plan.md
```

~40 lines, and the fastest place to catch the new failure mode: agy solving a different
problem from the one specified. Check:

- **Every acceptance criterion is addressed.** One that is missing from the plan is
  almost certainly missing from the diff.
- **The `## Decisions` entries are judgement calls, not scope cuts.** "Chose X over Y
  because the existing code does X" is the loop working. "Skipped X for simplicity" is a
  fix round.
- **The files it chose match the pattern map** in `AGENTS.md`. A plan that invents a new
  shape where an existing one fits produces a diff that compiles and still needs redoing.

**A missing `.agy-plan.md` means agy skipped the work loop.** Do not compensate by reading
the diff harder — treat it as a failed run and go to step 4.

If the plan is wrong, stop here. Reviewing a diff built on a misread spec wastes the
reading.

## 2. Read the diff

```bash
git -C ../DanceBook-agy-<N> status --short
git -C ../DanceBook-agy-<N> diff
```

agy is told not to use git, so its work appears as **uncommitted and untracked files**,
not as commits. Read new files directly; `diff main...HEAD` will show nothing.

Read the actual diff. The agent's own summary is a claim, not evidence — and so is its
plan, which says what it intended, not what it did.

**An empty diff means `--add-dir` was missing or ineffective** — agy worked in a
scratch directory and its summary describes files it never touched. Stop and fix the
invocation rather than reviewing an empty change.

## 3. Build it yourself

```bash
cd ../DanceBook-agy-<N> && ./gradlew build --rerun-tasks
```

**`--rerun-tasks` is not optional here.** agy has just built in this same clone, so a plain
`./gradlew build` finds every task up to date and exits successfully in a few seconds
without executing a single test. It looks exactly like a passing independent build and
verifies nothing — it is only replaying agy's own result back at you. If the build returns
suspiciously fast, or reports most tasks `up-to-date`, you have not verified anything yet.
`./gradlew clean build` works too and is slower.

agy is expected to have run the build itself and fixed its own failures — the `Stop` gate
in `.agents/hooks.json` will not let a delegated run end until a full build has gone green
since its last edit. Run it anyway: the gate proves a build passed at some point in the
run, not that the diff in front of you is green, and agy reports `SUCCESS` for runs that
did nothing. A failure here means it stopped early, and that is a fix round, not a
judgement call.

**If the run ended with no green build at all**, check whether the clone predates the gate
(`test -f ../DanceBook-agy-<N>/.agents/hooks.json`) — an old clone has no gate, and
re-cloning is cheaper than diagnosing the run.

Prefer `./gradlew test --tests "*TheNewTest*" --rerun-tasks` first for a fast signal, then
the full build before opening the PR.

### Review against the codebase's real rules

Check each one that the diff touches. These matter more now that agy chose the design
itself rather than following a plan Claude wrote:

- **Flyway** — any entity field change has a matching `V<next>__*.sql`. `ddl-auto=validate`
  means a miss is a startup crash, not a warning.
- **Domain events** — every new mutating service method publishes its `DomainEvent`.
  A miss is invisible: the feature works, but silently never reaches the activity feed.
- **HTMX** — new list/filter endpoints return a fragment selector on `HX-Request` and skip
  loading dropdown data, per `controller/web/DanceFigureWebController.kt`.
- **Navbar** — new top-level routes have an `activeNav()` branch in `NavbarAdvice.kt`.
- **Tailwind** — design tokens only, never raw palette values and never a hex literal in
  Kotlin or JS; `outline` on controls and `outline-variant` only on decoration; class names
  as whole literals, never assembled by concatenation (there is no safelist — `input.css`
  pins its sources with `@source` globs covering templates, `static/js` and
  `src/main/kotlin`); `static/css/output.css` untouched.
- **CSP** — new external scripts/styles are allowlisted in `config/SecurityConfig.kt`.
- **Tests** — new service logic has JUnit 5 + Mockito coverage in the existing style, and
  no `@DataJpaTest` slice has crept in.
- **Scope** — nothing edited outside the issue, especially not `AGENTS.md` / `CLAUDE.md`.

## 4. Request changes, or accept

Write numbered, specific change requests to `.agy-review.md` in the worktree. Name which
kind of failure each one is, because they need different feedback:

- **Plan wrong** — name the acceptance criterion it missed or misread, and quote it. Cheap
  to fix. It usually means the spec was ambiguous, so note that too: the next spec should
  close the gap rather than the next reviewer catching it again.
- **Diff wrong** — the plan was sound but the code is not. Specific, numbered, pointing at
  the rule or pattern it broke.

Then **resume the same conversation** — this reuses the cached context instead of
re-paying ~31k tokens of onboarding:

```bash
cd ../DanceBook-agy-<N> && agy --add-dir "$PWD" \
    --conversation <conversation_id> --output-format json --print-timeout 45m \
    -p='Read .agy-review.md and address every numbered item. Update .agy-plan.md to match what you actually did. Then re-run ./gradlew build. The runtime will send it to the background - that is normal, so do not relaunch it; wait for its completion notification and fix failures until it passes. End your reply by quoting the last two lines of that build verbatim.' \
    > .agy-run.json 2>&1
```

`--add-dir` is required on resumes too — without it agy edits a scratch directory and
reports success against files it never touched. Record quota before and after with
`python3 .claude/skills/delegate-to-agy/usage.py`.

Background it, then re-validate with `delegate-to-agy/validate-run.py` and return to step 1.

**Cap at 2 fix rounds.** If it is still wrong, stop and bring it to the user with a
diagnosis. A third round usually means the spec was ambiguous in a way more quota will not
fix — say which part, so the issue can be rewritten rather than retried.

## 5. Open the PR

```bash
gh pr create --title "..." --body "..."
```

The body should carry: what changed and why, `Closes #<N>`, a link to agy's plan comment on
the issue, a note that it was implemented by agy and reviewed by Claude, the verbatim build
result, and anything you want the user to look at closely. Then:

```bash
gh issue edit <N> --remove-label ready-for-agent --add-label ready-for-human
```

The workspace is a clone, so bring the work back yourself: branch off `main` in the main
repo, copy the changed files across, commit, push and open the PR. Re-run the tests on
that branch before pushing — you are verifying the code in its real destination, not the
throwaway clone. Then `rm -rf ../DanceBook-agy-<N>`.
