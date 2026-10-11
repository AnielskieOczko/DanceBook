# Assistant preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Shape of this plan.** It is deliberately written at task level, not file by file. In this
> repository the implementer (`agy`, via `delegate-to-agy`) explores the codebase and chooses the
> files itself; a plan that names the edits moves that cost back onto Claude. Each increment below
> becomes one GitHub issue written as a specification (see "Issues to open"). Landmarks are named
> only so the implementer starts in the right neighbourhood.

**Goal:** Real per-user assistant preferences (eight settings) that change assistant and search behaviour, editable on the profile page.

**Architecture:** A one-row-per-user `user_assistant_preferences` table behind a single `AssistantPreferencesService`, which folds in the global API-key check. Each feature asks the service where it already decides; the profile's Assistant accordion edits the row through one save endpoint.

**Tech Stack:** Kotlin, Spring Boot, Thymeleaf/HTMX, Flyway, Spring AI (Google GenAI), JUnit 5 + Mockito, Tailwind (`frontend/input.css`).

**Spec:** `docs/superpowers/specs/2026-10-11-assistant-preferences-design.md`

## Global Constraints

- Defaults live in code: all booleans on except "full window" (off); reply language `APP`. No admin defaults screen.
- A missing `GOOGLE_AI_API_KEY` always wins over a saved value (`AssistantFeature.enabled`).
- "Include shared items" only narrows. It extends the single `visibleTo` specification; never a second copy of the access rule (`AGENTS.md`, *Access control*).
- No transaction may span an LLM or embedding call.
- Flyway: next migration is **V44** (highest today is V43); `ddl-auto=validate`, so every entity field needs its migration.
- Use the `fragments/` catalog, icons via the `icon` fragment with `cls`, semantic variants, global design tokens, and the shared focus style (new text-entry classes go on the global `:focus-visible` exclusion list). en and pl strings with key parity; keys read by script go through the JS i18n bundle.
- Services only, never repositories, from controllers and assistant tools.
- Verify in the running app (run-dancebook) at desktop and 375px, in en and pl; the build must also be green.
- A Material is called a "Note" in the UI.

## Review Focus

- **No row yet:** a user who never saved has defaults; reading must not create a row or fail.
- **Preferences read fails:** callers fall back to defaults (everything on), not to "off".
- **Key missing but a saved "on":** assistant and dependent features stay off and the profile explains why.
- **Anonymous visitor:** search and related notes behave as today, with no preference lookup crash.
- **Invalid save:** an unknown reply language or malformed request is a 400 and stores nothing.
- **History off, then draft card:** a draft or card that refers to a conversation must not break when the conversation is session-only.
- **Include shared off, admin:** an admin's results narrow to owned items too, and never show more than the access rule allows.

---

## Increment 1: storage, service, profile rows, simple enforcements

Deliverable: a user can change six preferences on the profile page and they take effect.

### Task 1: Preferences table and service

- **Outcome:** `user_assistant_preferences` (V44) and `AssistantPreferencesService.forUser(user)` returning an immutable `AssistantPreferences` that already includes the global-key override. Defaults as in Global Constraints; anonymous gets defaults; read failure gets defaults.
- **Tests (service level, JUnit 5 + Mockito, existing style):** no row gives defaults and creates nothing; a saved row is read back; key missing forces assistant-dependent flags off; a repository exception gives defaults; anonymous gives defaults.
- **Landmarks:** `V43__add_medal_badge_style_to_app_user.sql` and the user-level badge-style code as the nearest example of a per-user choice; `AssistantFeature`.
- [ ] Write the tests, see them fail, implement, see them pass, commit.

### Task 2: Save endpoint

- **Outcome:** one `POST /profile/assistant-preferences` that validates and stores, returns the updated accordion row fragment, 400 on unknown values, no domain event.
- **Tests:** valid save then read-back; unknown reply language is 400 with nothing stored; saving for another user's id is impossible (the endpoint only ever acts on the current user).
- **Landmarks:** `ProfileController` (locale and badge-style saves) for the pattern.
- [ ] Tests first, implement, commit.

### Task 3: Profile Assistant accordion rows

- **Outcome:** rows for the six increment-1 preferences in the profile page, each showing its current value and expanding in place, saving on change. With no API key the accordion shows one line and hides the toggles; dependent rows are disabled with the reason. If #229 is not merged, this is an Assistant section on the current profile page.
- **Tests:** rendering test in en and pl (rows present, key-missing state, disabled state); JS i18n key test for any string a script reads.
- **Verify:** the running app at desktop and 375px, both languages, screenshot next to the mock (`docs/mocks/profile/`, direction C).
- [ ] Tests, implement, run-dancebook check, commit.

### Task 4: Enforce the six simple preferences

- **Outcome:**
  - assistant on: user-level `AssistantFeature` check, `/assistant/*` 404, markup hidden;
  - semantic search: `HybridSearchService.search` keyword-only, no embedding call;
  - related notes: related-notes methods empty before embedding, template block not rendered;
  - suggest figures: button not offered, `/figures/suggest` refuses;
  - full window: initial assistant surface state set server-side, expand button still works;
  - reply language: assistant system prompt follows `APP`, `PL` or `EN`.
- **Tests (one per enforcement):** search with the preference off makes no embedding call (verify the embedding collaborator is never invoked) and returns keyword hits; related notes off returns empty and renders no block; suggest endpoint off refuses; assistant off gives 404; the reply-language instruction is in the prompt for each value; full-window state appears in the rendered surface.
- **Review Focus tests live here:** anonymous search, preferences read failure, key missing with a saved "on".
- [ ] Tests, implement, run-dancebook check, build green, commit.

---

## Increment 2: remember history

Deliverable: the "Remember chat history" switch works.

### Task 5: Find out what "do not persist" costs, then implement it

- **First step, before any edit:** read how `AssistantConversationService` stores conversations, how the history rail lists them, and what drafts and cards reference. Record in `.agy-plan.md` whether a session-only conversation mode is small or touches those. If it is large, stop and bring the finding to the user rather than widening scope.
- **Outcome:** with the switch off, new conversations are not saved or listed after the session; saved chats already present stay until deleted; turning it back on resumes saving.
- **Tests:** new chat with the switch off is absent from the history list afterwards; existing chats remain; draft and card actions still work in a session-only conversation; switch back on saves again.
- [ ] Investigate and record, tests, implement, run-dancebook check, commit.

---

## Increment 3: include shared items

Deliverable: the "Include shared items" switch narrows what the assistant and semantic search read.

### Task 6: Owned-only narrowing

- **Outcome:** when off, assistant read tools and semantic retrieval use only items the user owns, by adding an owner condition to the existing `visibleTo` path and to the knowledge-chunk query (chunks carry `owner_id`). Figures are unaffected (no owner).
- **Tests:** for notes and choreographies, each of private-own, private-other, public, shared-to-me, and admin: with the switch on the results equal today's; with it off only owned items appear and never more than the access rule allows; semantic retrieval and the assistant tools agree.
- [ ] Tests, implement, run-dancebook check, build green, commit.

---

## Issues to open (as specifications, label `ready-for-agent` when the user says go)

Each is a spec per `delegate-to-agy` (why, behaviour, acceptance criteria, out of scope, decisions), not a copy of the tasks above:

1. *Assistant preferences, step 1* — Tasks 1 to 4. Depends on the spec; profile placement depends on whether #229 has merged.
2. *Assistant preferences: remember history* — Task 5. Starts with the investigation.
3. *Assistant preferences: include shared items* — Task 6.

## Self-review

- Spec coverage: storage, service, key override, all eight preferences, the profile UI, errors and defaults, tests, slicing: Tasks 1 to 6.
- No placeholders: file-level details are left to the implementer on purpose (project rule), not omitted by accident.
- Names consistent: `AssistantPreferencesService.forUser`, `AssistantPreferences`, `user_assistant_preferences`, `POST /profile/assistant-preferences`.
