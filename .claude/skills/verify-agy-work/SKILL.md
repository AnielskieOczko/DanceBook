---
name: verify-agy-work
description: Use after Antigravity (agy) has implemented an issue, to review the diff, independently confirm the build, request changes, and open the PR. Trigger on "verify agy's work", "review the agy branch", or /verify-agy-work.
---

# Verify agy's work and ship it

Claude's job here is the part Gemini Flash should not be trusted with: judging whether the
diff is *correct and idiomatic for this codebase*, not merely whether it compiles.

## 1. Read the diff

```bash
git -C ../DanceBook-agy-<N> diff main...HEAD
git -C ../DanceBook-agy-<N> diff --stat main...HEAD
```

Read the actual diff. The agent's own summary is a claim, not evidence.

## 2. Build it yourself

```bash
cd ../DanceBook-agy-<N> && ./gradlew build
```

**Never** accept "the build passes" from the run summary — agy reports `SUCCESS` even for
runs that did nothing. Run it and read the output. If it fails, that is a fix round, not a
judgement call.

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
cd ../DanceBook-agy-<N> && GRADLE_USER_HOME=$PWD/.gradle-home \
agy --sandbox --conversation <conversation_id> --output-format json --print-timeout 45m \
    -p='Read .agy-review.md and address every numbered item. Re-run ./gradlew build until it passes.' \
    > .agy-run.json 2>&1
```

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

Leave the worktree in place until the PR merges, then `git worktree remove ../DanceBook-agy-<N>`.
