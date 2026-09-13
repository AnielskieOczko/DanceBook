---
name: verify-agy-work
description: Use after Antigravity (agy) has implemented an issue, to review the diff, independently confirm the build, request changes, and open the PR. Trigger on "verify agy's work", "review the agy branch", or /verify-agy-work.
---

# Verify agy's work and ship it

Claude's job here is the part Gemini Flash should not be trusted with: judging whether the
diff is *correct and idiomatic for this codebase*, not merely whether it compiles.

## 1. Read the diff

```bash
git -C ../DanceBook-agy-<N> status --short
git -C ../DanceBook-agy-<N> diff
```

agy is told not to use git, so its work appears as **uncommitted and untracked files**,
not as commits. Read new files directly; `diff main...HEAD` will show nothing.

Read the actual diff. The agent's own summary is a claim, not evidence.

**An empty diff means `--add-dir` was missing or ineffective** — agy worked in a
scratch directory and its summary describes files it never touched. Stop and fix the
invocation rather than reviewing an empty change.

## 2. Build it yourself

```bash
cd ../DanceBook-agy-<N> && ./gradlew build
```

agy is expected to have run this itself and fixed its own failures. Run it anyway — it
reports `SUCCESS` for runs that did nothing, so its claim is not evidence. A failure here
means it stopped early, and that is a fix round, not a judgement call.

Prefer `./gradlew test --tests "*TheNewTest*"` first for a fast signal, then the full
build before opening the PR.

## 3. Review against the codebase's real rules

Check each one that the diff touches:

- **Flyway** — any entity field change has a matching `V<next>__*.sql`. `ddl-auto=validate`
  means a miss is a startup crash, not a warning.
- **Domain events** — every new mutating service method publishes its `DomainEvent`.
  A miss is invisible: the feature works, but silently never reaches the activity feed.
- **HTMX** — new list/filter endpoints return a fragment selector on `HX-Request` and skip
  loading dropdown data, per `controller/web/DanceFigureWebController.kt`.
- **Navbar** — new top-level routes have an `activeNav()` branch in `NavbarAdvice.kt`.
- **Tailwind** — Noble Harmony tokens only; no classes generated from `static/js/*.js`
  without a safelist; `static/css/output.css` untouched.
- **CSP** — new external scripts/styles are allowlisted in `config/SecurityConfig.kt`.
- **Tests** — new service logic has JUnit 5 + Mockito coverage in the existing style.
- **Scope** — nothing edited outside the issue, especially not `AGENTS.md` / `CLAUDE.md`.

## 4. Request changes, or accept

On rejection, write numbered, specific change requests to `.agy-review.md` in the worktree,
then **resume the same conversation** — this reuses the cached context instead of re-paying
~31k tokens of onboarding:

```bash
cd ../DanceBook-agy-<N> && agy --add-dir "$PWD" \
    --conversation <conversation_id> --output-format json --print-timeout 45m \
    -p='Read .agy-review.md and address every numbered item. Re-run ./gradlew build until it passes.' \
    > .agy-run.json 2>&1
```

`--add-dir` is required on resumes too — without it agy edits a scratch directory and
reports success against files it never touched. Record quota before and after with
`python3 .claude/skills/delegate-to-agy/usage.py`.

Background it, then re-validate with `delegate-to-agy/validate-run.py` and return to step 1.

**Cap at 2 fix rounds.** If it is still wrong, stop and bring it to the user with a
diagnosis — a third round usually means the issue's plan was underspecified, and more
quota will not fix that.

## 5. Open the PR

```bash
gh pr create --title "..." --body "..."
```

The body should carry: what changed and why, `Closes #<N>`, a note that it was implemented
by agy and reviewed by Claude, the verbatim build result, and anything you want the user to
look at closely. Then:

```bash
gh issue edit <N> --remove-label ready-for-agent --add-label ready-for-human
```

The workspace is a clone, so bring the work back yourself: branch off `main` in the main
repo, copy the changed files across, commit, push and open the PR. Re-run the tests on
that branch before pushing — you are verifying the code in its real destination, not the
throwaway clone. Then `rm -rf ../DanceBook-agy-<N>`.
