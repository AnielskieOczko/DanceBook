# Assistant preferences (per-user settings)

Status: design approved in conversation, spec awaiting review. Part of #201 (user menu and
profile); builds on #229 (profile page rework, direction C) and #292 (per-user badge style).

## Why

The profile mock shows assistant switches, but nothing behind them exists. The assistant and
the semantic features always run for everyone: semantic results in search (#233), related
notes, figure suggestions, stored chat history. A user cannot turn any of them off, choose how
the assistant opens, or limit what it reads. This adds real, working per-user preferences.

## Scope

Eight preferences, all implemented, none decorative:

| Preference | Values | Default |
|---|---|---|
| Assistant on | boolean | on |
| Semantic search in standard search | boolean | on |
| Related notes on pages | boolean | on |
| Suggest figures (note page button) | boolean | on |
| Remember chat history | boolean | on |
| Open assistant in full window | boolean | off (side panel) |
| Include shared items | boolean | on |
| Assistant reply language | `APP`, `PL`, `EN` | `APP` |

"Assistant on" and every feature that needs the knowledge index or the LLM are also bound by
the global `GOOGLE_AI_API_KEY` check (`AssistantFeature.enabled`). **A missing key always wins**
over a user's choice.

Defaults are fixed in code, chosen so nobody sees a change until they opt out. There is no admin
default screen (decided: not needed, unlike the medal badge style).

## Out of scope

- Admin-set defaults, retention periods for history, per-conversation controls.
- Any change to what the assistant can do (tools, drafts, grounding).
- Changing the profile layout itself; that is #229.

## Design

### Storage and the single entry point

- Table `user_assistant_preferences`, one row per user, primary key the user id, typed boolean
  columns plus `reply_language`. No row means defaults; a row is written on first save.
  One Flyway migration. `AppUser` is untouched.
- `AssistantPreferencesService.forUser(user)` returns an immutable `AssistantPreferences` that
  already folds in the global key, so callers never combine the two themselves. Anonymous
  callers get the defaults. A read failure falls back to the defaults (everything on) rather
  than turning the assistant off.
- Saving is one `POST /profile/assistant-preferences`. Unknown values are rejected with 400.
  It returns the profile accordion row with the new value, the same pattern as the existing
  locale and badge-style saves. No domain event is published.

### Where each preference takes effect

Every feature asks the service at the single place it already decides.

- **Assistant on:** `AssistantFeature` check and `/assistant/*`. Off behaves as no key for this
  user: markup hidden, routes 404.
- **Semantic search:** `HybridSearchService.search` returns the keyword-only result it already
  returns on any embedding failure, and makes no embedding call.
- **Related notes:** the related-notes methods of `KnowledgeRetrievalService` return empty
  before embedding; the note and figure templates do not render the block.
- **Suggest figures:** `MaterialWebController` stops offering `suggestionsAvailable` and the
  `/figures/suggest` endpoint refuses.
- **Remember history:** `AssistantWebController`'s conversation store. New conversations are
  session-only: not saved, not listed in the history rail. Saved chats already there stay until
  the user deletes them.
- **Full window:** the server sets the assistant surface's initial state; the expand button
  still works for the session.
- **Include shared items:** narrows the assistant read tools and semantic retrieval to items the
  user owns. It only narrows. It extends the single `visibleTo` specification with an owner
  condition; it must not add a second copy of the access rule (see *Access control* in
  `AGENTS.md`). Figures have no owner and are unaffected.
- **Reply language:** the assistant system prompt. `APP` uses the current UI locale; `PL` or
  `EN` forces it.

### Profile UI

Rows in the profile's Assistant accordion, direction C of the approved mock. Each row shows its
current value and expands in place; a toggle saves on change. With no API key the accordion shows
one line, "The assistant isn't set up on this server", and the toggles are hidden. Rows for
features that need the key are disabled with that reason. Strings in en and pl with key
parity; keys read by script go through the JS i18n bundle.

## Acceptance criteria

- [ ] Each of the eight preferences can be changed on the profile page and the change takes effect
      as described above, for that user only.
- [ ] With semantic search off, a standard search makes no embedding call and returns keyword
      results; related notes off renders no block and makes no call.
- [ ] With suggest figures off the button is absent and the endpoint refuses.
- [ ] With remember history off, a new conversation is absent from the history rail after the
      session; existing saved chats remain.
- [ ] With include shared off, the assistant and semantic results contain only the user's own
      notes and choreographies, tested for private, public, shared and admin; never more than
      the access rule already allows.
- [ ] A missing API key disables the assistant section and every dependent feature regardless of
      saved values.
- [ ] A failed save keeps the old value and shows an error in the row; a failed read uses the
      defaults.
- [ ] Verified in the running app at desktop and 375px in en and pl; `./gradlew build` green.

## Slicing (each step usable on its own)

1. Table, service, save endpoint, profile rows, and the simple enforcements: assistant on,
   semantic search, related notes, suggest figures, full window, reply language. If #229 is not
   done, this adds an Assistant section to the current profile page and moves into the new layout
   with #229.
2. Remember history.
3. Include shared items.

## Decisions for the implementer

- Remember history is the riskiest step: first check whether a "do not persist" conversation mode
  is a small change or touches the history rail and the draft cards, which reference a
  conversation. Record the finding in the plan before editing.
- Reply language `APP` means the request's resolved locale at call time, not a stored copy.
- Where the preference service is called inside a transaction that also calls out to an LLM or
  embedding provider, keep the call outside it, as the existing assistant code does.
