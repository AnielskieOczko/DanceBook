# Home dashboard rebuild — design

**Status:** approved in brainstorming, ready for an implementation plan
**Mocks:** https://claude.ai/artifact/7HhkTzZwbQMx2qknJVrhUY. The bottom row, *"Chosen mix"*, is
the target: `Mix-Mobile` for phones and `Mix-Desktop-Collapsible` for desktop. The rows above
it are the directions that were rejected.
**Related:** #142 (pinning figures to a note, no timestamps), #141 (note view rework). The chat
bar in the mocks belongs to the assistant spec
(`2026-09-25-ai-assistant-design.md`) and is **not** part of this work.

## Problem

The home page (`templates/index.html`, `controller/HomeController.kt`) answers no question the
user actually has when opening the app:

- **Four count tiles** (Materials, Figures, Styles, Feedback) show site-wide totals from
  `repository.count()`. They are not the user's own numbers, and none of them prompts an action.
  "Feedback" isn't even a link.
- **The header says "Good morning" at any hour**, above boilerplate copy.
- **A 10-item activity timeline** takes up most of the page, and ~80% of the template's markup
  is an inline `th:switch` over event types, duplicated from the notifications page's needs.
- **The quick actions are New Material and Create Collection**, which are not the most common
  actions. Training and figures are absent.

## Governing principle

**DanceBook exists to build knowledge, and a Note is the unit of knowledge.** The user mostly
opens the app *after* a session to wrap it up: confirm attendance, and capture what happened as
a Note. The home page is organised around that moment first, then around finding recent
knowledge again.

## Content, top to bottom (phone)

1. **Header.** A greeting that follows the actual time of day (morning, afternoon, evening,
   computed server-side in the user's zone), the date, and the profile button.
2. **Week strip.** Monday to Sunday of the current week. Each day shows its state:
   - **attended**: filled accent
   - **to confirm**: warning, dashed
   - **planned**: outlined
   - **today**: ringed
   - **nothing**: plain

   Below it are the streak ("4-week streak", from `TrainingStats.currentStreak`) and the number
   of sessions waiting to be confirmed.
3. **To wrap up (the hero).** The most recent session still waiting for confirmation, as a large
   accent card. It shows date, time, title and category segments, with **Attended**,
   **Skipped** and **Write a note from this session**. Older unconfirmed sessions follow as
   compact rows with icon-only attended/skipped buttons. **When nothing is waiting**, this slot
   shows the **next upcoming session** instead (and its linked note, if any). When there is no
   next session either, it shows an empty state with a link to schedule one.
4. **Recent notes.** The latest notes the user created or edited. On a phone they scroll
   sideways as cards; on desktop they sit in a grid. Each card shows the dance, a relative
   date, the title, a two-line excerpt of the text, and **each pinned figure with its timing**
   (Feather Step · S Q Q). The figure detail is required, per the rule that DanceBook designs
   keep domain detail. "All notes" links to the notes list.
5. **This month.** Time trained, sessions attended and attendance rate, taken from
   `TrainingStatsService` for the current month. On desktop it adds a category breakdown bar.
   The whole card links to the stats page.
6. **Continue.** The two or three most recently edited choreographies and collections.

**Removed:** the count tiles, the activity timeline (the notifications page already exists and
keeps it), and the two fixed quick-action buttons. A single **"+ New"** button (note, session,
figure, choreography, collection) replaces them. On a phone it sits in the header; on desktop it
sits in the page header.

## Layout

**Phone (below 1024px).** One column, in the order above. The bottom tab bar has **four items:
Home, Training, Notes, Figures**, and replaces the current mobile navigation for these
destinations. Choreographies, Collections, Admin and the profile stay reachable from the
profile menu. Touch targets are at least 44px.

**Desktop (1024px and up).** Sidebar navigation on the left (the current sidebar, restyled to
the mocks). The content is a three-column grid:
- the header, then the week strip across the full width
- **To wrap up** spanning two columns, with **This month** in the third
- **Recent notes** (three cards) spanning two columns, with **Continue** in the third

Bottom padding is reserved for the assistant's floating bar, which ships with the assistant.

**Visual language.** The current tokens (`#1f4b4a` accent, warm neutrals, `warning` reserved
for unconfirmed), Source Serif 4 for page and section headings, and Inter for everything else,
as in the mocks. Everything uses the fragment catalog: `pageHeader`, `sectionTitle`, `card`,
`statCard`, `attendanceBadge`, `icon`, `linkButton` / `actionButton`. A new visual component
becomes a new fragment in `templates/fragments/`, **and is added to
`FragmentCatalogRenderingTest`**.

## Behaviour

**Attended / Skipped** reuse the existing attendance update for a session (the same service
call the training views use, so the `TrainingRecord` write and the activity event happen
exactly as they do today). The update runs through HTMX and swaps the wrap-up section in place:
the next waiting session moves up, or the next upcoming session appears.

**Write a note from this session** opens the note create form **prefilled from the session**:
- title `"<session title> — <date>"`
- dance style from the session's first segment's category, where one maps
- on save, the note is linked to the session (`TrainingEvent.material`)

If the session already has a linked note, the button opens that note instead and reads **Open
note**. Figures are pinned on the note page using #142's picker. This spec does not duplicate
that picker.

**Data scoping.** Everything on the page is the current user's: their sessions (as the stats
page scopes them today), notes they created, and their own choreographies and collections.
Sessions follow the active calendar context (`ActiveCalendarService`), exactly as the stats
page does.

## Architecture

- **`HomeController` becomes thin.** It calls a new `DashboardService` (interface plus
  `DashboardServiceImpl`, as the repo convention requires) that returns one `DashboardView`
  DTO: greeting, week days, wrap-up list, next session, recent notes, month stats, continue
  items. The controller no longer reads repositories directly.
- **Each section is its own fragment** under `templates/home/`, so HTMX can swap the wrap-up
  section after an attendance change without re-rendering the page.
- **The greeting's time of day is computed from an injected `Clock`**, so tests can pin it.

## Error and empty states

- A new user with no sessions and no notes sees an empty wrap-up state ("Schedule your first
  session") and an empty notes state ("Write your first note"), both from `fragments/empty`.
- If an attendance update fails (for example, a calendar sync error), the section re-renders
  with the error alert from `fragments/alert`, and the session stays unconfirmed.

## Testing

- `DashboardServiceImpl` unit tests:
  - the wrap-up list is ordered newest first
  - it falls back to the next session when nothing is waiting
  - the week strip maps each day's state, with unconfirmed taking precedence as in
    `TrainingEventPalette`
  - the greeting boundaries, using a fixed `Clock`
- A web-layer test: the home page renders for a user with no data, and for one with data.
  Attending from the hero swaps the section and writes the `TrainingRecord`.
- Rendering-test coverage for every new fragment.
- `./gradlew build` is green.

## Out of scope

- The assistant chat bar, sheet and panel (the assistant spec).
- Changing the notifications page or the activity feed's storage.
- Per-user attendance on shared sessions (the access-control epic).
