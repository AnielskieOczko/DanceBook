# Calendar as a view context — design

**Status:** approved in brainstorming, ready for an implementation plan
**Follows:** #52 (calendars as first-class entities) and #58 (per-session picker, verification),
both on PR #57. This design **removes** the per-session picker that #58 added.
**Does not depend on:** #53. Inbound sync remains unbuilt and unaffected.

## Problem

#52 made a Google calendar a database row rather than the `GOOGLE_CALENDAR_ID` environment
variable. It was scoped around what inbound sync would need — per-calendar sync tokens — and
that turned out to be the wrong boundary. On first use the feature is close to unusable:

- **Nothing in the app is per-calendar.** The agenda, the calendar grid, statistics, the
  timeline and the history table all show every calendar's sessions merged together, with no
  way to look at one calendar. Adding a second calendar therefore changes almost nothing you
  can see.
- **The only way to target a calendar is a dropdown buried in the create-session form** (#58).
  That is the wrong shape: a calendar is a context you work in, not a per-record attribute you
  set one session at a time.
- **The admin panel cannot edit or delete a calendar.** Deletion was scoped out of #52 on the
  grounds that it would have to decide the fate of dependent events. Deciding that is the work,
  not a reason to skip it.

## Governing principle

**A calendar is a context you are working in, not a field on a session.** You choose it once,
and every training view answers for that calendar until you choose differently. Everything else
in this design follows from that sentence.

## Decisions

**One active calendar at a time, plus "All calendars".** Not Google-style multi-select
checkboxes. A single active context is what gives "which calendar does a new session go to?" an
unambiguous answer, and that answer is what lets the per-session picker be deleted rather than
merely hidden. Under "All", a new session falls back to the default calendar.

**"All calendars" means every linked calendar, including disabled ones.** It is an app-side
union of `training_calendar` rows — it has nothing to do with Google's own notion of "all
calendars". No session is ever invisible in every view.

**The selector offers a calendar if it is enabled, or if it owns at least one session.** A
calendar that failed verification is saved disabled and owns nothing, so it does not appear —
offering it would be pointless, since creating a session in it fails by design. But a calendar
that worked for months and was later disabled keeps its sessions reachable, shown as
`Name (disabled)` and view-only. The blunter rule — hide every disabled calendar — would make
disabling one silently erase real training history from the agenda and shrink the statistics,
which is precisely the kind of quiet data loss `training_record` exists to prevent.

**Statistics, timeline and history follow the active calendar**, which requires the calendar to
be denormalised onto `training_record` (see V30 below). The cheaper alternative — scoping by
joining records to their events — was rejected: `training_record` deliberately outlives the
session it came from, so a join would silently drop every orphaned record from per-calendar
figures, and deleting a session would quietly reduce your recorded hours.

**Deleting a calendar removes its sessions locally and leaves Google untouched.** Their
`training_record` history survives, marked orphaned, so hours and statistics never shrink. The
Google calendar and its events are not ours to destroy — we are removing our mirror of it, not
it.

**The Google Calendar ID of an existing calendar is editable only while it owns no sessions.**
Once sessions exist, every stored `google_event_id` belongs to the old Google calendar, and
repointing the row would strand all of them. The display name is always editable.

## The active calendar

### `ActiveCalendarService`

```kotlin
interface ActiveCalendarService {
    /** The calendar scoping the training views, or null for "All calendars". */
    fun active(): TrainingCalendar?

    /** Sets the active calendar; null selects "All calendars". */
    fun setActive(calendarId: UUID?)

    /** Enabled calendars, plus any disabled calendar that still owns sessions. */
    fun selectable(): List<TrainingCalendar>

    /** Where a new session goes: the active calendar, or the default under "All". */
    fun creationTarget(): TrainingCalendar
}
```

`creationTarget()` throws when the active calendar is one of the view-only disabled ones,
carrying the message *"Club Training is disabled — choose another calendar to create a
session."* It deliberately does **not** silently fall back to the default: you asked for a
session in the calendar you are looking at, and quietly filing it somewhere else is worse than
refusing. The "New session" control is hidden while a disabled calendar is active, so the
message is a backstop rather than the normal path.

The selection lives in the `HttpSession` under one key, holding either a calendar id or the
literal `ALL`. An absent key means "not yet chosen" and resolves to the default calendar, so a
first visit behaves exactly as the app does today. The implementation injects `HttpSession`;
Spring supplies a session-scoped proxy to the singleton service.

This is a view preference rather than durable data, which is why it is not a column on
`app_user`. The cost is that it resets on logout. Promoting it to a user column later is a
contained change behind this interface.

`creationTarget()` resolving "All" to the default is the one place the old behaviour survives,
and it is why removing the picker loses no capability.

### Exposing it to the templates

A `TrainingCalendarContextAdvice`, a `@ControllerAdvice` restricted to the four training web
controllers via `assignableTypes`, supplies `activeCalendar` and `selectableCalendars`:

- `controller/web/TrainingEventWebController.kt` (agenda, calendar grid, forms)
- `controller/web/TrainingStatsWebController.kt`
- `controller/web/TrainingTimelineWebController.kt`
- `controller/web/TrainingHistoryWebController.kt`

It is deliberately **not** added to `NavbarAdvice`, which supplies attributes to every page in
the app — calendars are irrelevant to the syllabus, materials and choreography pages, and
loading them there would be waste on every request.

### The selector

A shared `fragments/calendar-selector.html` rendered in the page header of all five training
views. Changing it issues `POST /training-events/active-calendar` with the chosen id (or `ALL`).
The endpoint stores the selection and responds `204 No Content` with an **`HX-Refresh: true`**
header, so htmx reloads whichever page the selector was on.

Returning a fragment instead would mean teaching one shared selector the target id and fragment
name of five heterogeneous views — the agenda's `#events-list`, the FullCalendar grid, and three
others that do not share a shape. `HX-Refresh` is one line, is correct on every page by
construction, and costs a page load on an action taken a few times a day. Because `activeNav()`
already distinguishes these routes, no navbar change is needed.

The selector renders only when `selectableCalendars` holds more than one entry, so a
single-calendar install sees no new control.

## Scoping the views

| View | Reads | Change |
| --- | --- | --- |
| Agenda list | `TrainingEventSpecification.withFilters` | add a `calendarId` parameter and predicate, alongside the existing event-type, category, attendance, search and awaiting-confirmation predicates |
| Calendar grid | `controller/api/TrainingCalendarApiController.kt` | filter the FullCalendar feed by the active calendar |
| Timeline | `service/TrainingTimelineServiceImpl.kt` | calendar-aware repository queries for its two-step paginated fetch |
| Statistics | `service/TrainingStatsServiceImpl.kt` | filter both `training_record` and `training_event` reads |
| History | `controller/web/TrainingHistoryWebController.kt` | filter `training_record` reads |

The agenda already filters server-side through a specification, so calendar becomes one more
predicate rather than a new mechanism. `null` (All) adds no predicate at all, which keeps the
existing query plans intact for the common case.

Statistics reconciliation invariants asserted by `TrainingStatsServiceTest` must continue to
hold **within** a calendar scope, not only globally.

## Schema: V30

```sql
ALTER TABLE training_record ADD COLUMN calendar_id   UUID;
ALTER TABLE training_record ADD COLUMN calendar_name VARCHAR(255);

CREATE INDEX idx_training_record_calendar ON training_record(calendar_id);

-- Backfill from each record's session, where that session still exists. Records already
-- orphaned keep a null calendar: the calendar they came from is genuinely unrecoverable.
UPDATE training_record r
SET calendar_id   = e.calendar_id,
    calendar_name = c.display_name
FROM training_event e
JOIN training_calendar c ON c.id = e.calendar_id
WHERE r.training_event_id = e.id;
```

**No foreign key**, matching the deliberate choice already documented on `training_event_id`:
the record outlives the things it refers to. `calendar_name` is denormalised for exactly the
reason `title`, `event_type` and `category_name` already are on this table — so the name
survives the calendar being deleted and history stays readable.

Unlike V29, this backfill is pure SQL over existing rows and needs no environment variable, so
it runs inside the migration rather than in a startup component.

`service/TrainingRecordWriter.kt` sets both columns from the event when it syncs a record, next
to where it already copies the title and event type.

Records with a null calendar appear under "All calendars" only.

## Creating sessions

`TrainingEventRequest.calendarId` **stays on the DTO**, but the web controller populates it from
`ActiveCalendarService.creationTarget()` instead of from a form field. The service layer keeps
its existing `resolveCalendar` and stays free of session state; the form loses the control.

Removed from #58:

- the calendar `<select>` in `templates/training-events/form.html`, its `th:disabled` edit
  handling and the "Cannot be moved between calendars" hint
- `calendars` / `defaultCalendarId` from `populateFormOptions`
- the three picker rendering assertions in `TrainingEventViewRenderingTest`

Kept from #58: `verifyCalendar`, the admin Verify button, the 403/404 messages and the add-form
help text.

Series behave identically — one form drives both, and every occurrence lands on the creation
target.

**Editing a session still cannot move it between calendars.** That constraint is unchanged and
unrelated to the picker: it needs Google's `events.move()`, which the client does not have.

## Admin CRUD

### Edit

Inline row editing, reusing the `userRow` / `editUserRow` fragment-pair pattern already in
`templates/admin/dashboard.html` with `hx-target="closest tr"`.

- **Display name** — always editable.
- **Google Calendar ID** — editable only while the calendar owns no sessions; the input is
  disabled otherwise, with a short reason. A changed id is re-verified before saving, and a
  failed verification disables the calendar and reports why, exactly as adding one does.

### Delete

`TrainingCalendarService.delete(id)`:

1. Reject if the calendar is the default **and** other calendars exist — "Make another calendar
   the default before deleting this one." Deleting the last remaining calendar is allowed; the
   app then has none, and session creation fails with the existing readable message.
2. Remove each of the calendar's sessions through `TrainingEventPersistence.remove`, so every
   `training_record` is orphaned rather than erased and the matching domain events are
   published.
3. Delete the `training_calendar` row.

Google is never called. The `ON DELETE RESTRICT` on `training_event.calendar_id` stays as the
database backstop; the service removes children first, so it is never hit in normal operation.

### The confirm dialog

Deletion must state how many sessions are going and that their history is kept. The existing
`data-confirm` hook in `static/js/main.js` is a bare `window.confirm` and cannot show a count,
so this needs a server-rendered dialog fragment. **Issue #47 needs the same component** for bulk
actions, so build it as a shared `fragments/confirm.html` rather than something admin-specific.
Tailwind only scans templates, so its classes must live in the template rather than be assembled
in JavaScript, and CSP forbids inline script and `onclick`.

## Testing

- `ActiveCalendarServiceTest` — unset resolves to the default; `ALL` yields null with the
  default as creation target; `selectable()` includes an enabled calendar, excludes a disabled
  one with no sessions, and includes a disabled one that owns sessions.
- `TrainingEventSpecificationTest` — the calendar predicate filters, and a null calendar adds no
  predicate.
- Scoping tests per view: statistics, timeline, history and agenda each return only the active
  calendar's data, and everything under "All".
- `migration/TrainingCalendarRecordMigrationTest` — the V30 backfill, in the
  `TrainingRecordBackfillTest` shape (raw Flyway plus Testcontainers, no Spring context): a
  record whose event survives gets that event's calendar and name; an already-orphaned record
  keeps a null calendar.
- Admin: delete removes sessions and orphans their records while leaving the records' hours
  intact; deleting the default with others present is rejected; the Google id is rejected when
  the calendar owns sessions.
- Rendering: the create form no longer contains `id="calendarId"`; the selector renders with two
  selectable calendars and not with one.

## Out of scope

Inbound sync (#53). Moving a session between calendars, which needs `events.move()`. Per-user
calendars. Persisting the active calendar across logins. Multi-select calendar overlay.

## Implementation split

This is one design but two issues, and they should be planned and reviewed separately:

**A — the calendar context.** `ActiveCalendarService`, the selector fragment and its endpoint,
the context advice, scoping all five views, V30 with its backfill, and removing the picker. This
is the half that changes what you see and touches the schema.

**B — admin CRUD.** Inline edit, delete with its session removal, and the shared
`fragments/confirm.html`. It depends on nothing in A and could be built first or in parallel;
keeping it separate means the migration and the destructive delete path get their own review.

## Sequencing

PR #57 (#52 plus #58) should merge first. It carries the startup-backfill foreign key fix and
the verification button already in use, and folding this work into it would make it
unreviewably large. The per-session picker therefore ships and is removed one PR later — mild
churn, accepted deliberately in exchange for two coherent reviews.

## Note for the user

Per-calendar statistics change the numbers currently on screen. Today's totals span every
calendar; once this lands, selecting a calendar shows a smaller figure and only "All calendars"
matches what is shown today. This is the intended behaviour, not a regression.
