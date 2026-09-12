# Google Calendar sync — design

**Status:** approved in brainstorming, ready for an implementation plan
**Depends on:** the training-record work on `feature/training-records` (PR #50), which this
design writes through and relies on for history safety.

## Problem

The calendar integration is write-through only. Every mutation in DanceBook goes to Google
Calendar and then to the local database, but nothing ever reads back:
`GoogleCalendarClient` exposes `createEvent`, `updateEvent` and `deleteEvent` and no list
operation at all. Google has no idea the app exists.

So an edit made in Google's own UI — moving a session, renaming it, deleting it, or adding a
new one on the phone — is invisible to DanceBook forever. The app and the calendar drift apart
silently, and the app is the only one of the two that can be corrected.

A second, related problem: the calendar is a single environment variable,
`GOOGLE_CALENDAR_ID`. Changing it leaves every stored `google_event_id` pointing at a calendar
the app no longer reads, with nothing to reconcile the two.

## The governing principle

**The app and its calendars always show the same state, deletions included.**

Calendar events are planning artefacts. They change constantly and get tidied — past events
cleared out, mistaken series thrown away — and that churn is expected. Letting the two systems
disagree in order to avoid losing data is the wrong trade: it leaves orphaned, disconnected
events in the app for the user to clean up by hand.

Statistics are not protected by keeping stale calendar rows alive. They are protected by
`training_record`, which persists every confirmed outcome and survives the deletion of the
session that produced it. So the answer to "won't deleting break the statistics?" is "no — that
is what the history table is for", never "so let us not delete".

## Decisions

**Calendars are first-class entities, not a config string.** A user can add calendars, choose
which one new sessions are created in, and stop syncing one without disturbing its events. Each
training event records which calendar it lives in.

**Inbound sync covers new Google events too.** Anything appearing on a synced calendar becomes
a DanceBook session. Creating a session on a phone in Google's UI is exactly the case worth
supporting.

**Google wins on conflict.** When the same session changed on both sides, the inbound change
overwrites the local row — whichever UI was to hand got the edit, and the last edit should
stick. That rule reaches less far than it sounds: Google has a title, a start and an end and
nothing else, so attendance, event type and style breakdown are not contestable.

**An adopted event is a plain `TRAINING` session.** Type `TRAINING`, status `PLANNED`, no style
breakdown. It behaves like a session created in the app and not yet detailed: once attended its
hours land in the existing "Unassigned" slice. No new "needs details" state is introduced.

**The reconciler is built first; the trigger is chosen separately.** Translating a changeset
into local writes is identical whether it arrived by polling, by push notification, or on a page
view.

**Sync on view is the first trigger.** A scheduled poller was rejected because the Cloud Run
deployment sets no `--min-instances`, so the service scales to zero and `@Scheduled` work cannot
fire reliably. (The existing `StorageCleanupJob` almost certainly has this problem already —
worth its own issue.) Push notifications via `events.watch` were rejected for now as the most
moving parts: a public unauthenticated endpoint, channel registration and renewal, token
verification — and the renewal job hits the same scale-to-zero problem. Adding `watch` later is
a new caller of the same reconciler.

**The audit trail stays in `activity_event`.** See "Why the history table is not an audit log".

## Calendars as entities

### `training_calendar`

| column | notes |
| --- | --- |
| `id` | UUID primary key |
| `google_calendar_id` | the Google id; unique |
| `display_name` | what the admin UI shows |
| `sync_token` | this calendar's incremental token, null until the first successful sync |
| `last_synced_at` | for display; not used for throttling |
| `is_default` | exactly one row is true: where app-created sessions go |
| `enabled` | whether inbound sync runs for it |
| `created_at`, `updated_at` | |

Sync state is per-calendar, and that is what makes switching safe by construction rather than by
special case. A newly added calendar has no token, so its first sync is a full one that adopts
its events. Another calendar's events are never in scope for it, so they cannot be touched — no
"the calendar id changed, suppress deletions this once" guard is needed.

### `training_event.calendar_id`

A non-null FK to `training_calendar(id)` once backfilled, `ON DELETE RESTRICT`: a calendar that
still owns events cannot be removed out from under them.

### Bootstrap and backfill

Flyway cannot read `GOOGLE_CALENDAR_ID`, because it is an environment variable rather than a
database value. So the migration adds the table and a **nullable** `calendar_id`, and a startup
component does the rest — the same shape as `AppUserServiceImpl` bootstrapping the root admin:

1. If `GOOGLE_CALENDAR_ID` is set and no `training_calendar` row matches it, insert one, marked
   default and enabled.
2. Point every `training_event` with a null `calendar_id` at that row.

`GOOGLE_CALENDAR_ID` is only ever a seed for the first row. After bootstrap the database is
authoritative, and calendars are managed in the UI.

All calendars are reached through the one existing OAuth credential, so any calendar added must
be visible to that Google account. A `403` or `404` when syncing a calendar disables it and
surfaces the reason rather than failing every page load.

### Writes follow the event's own calendar

`createEvent`, `updateEvent` and `deleteEvent` all take a calendar id. A new session is created
in the **default** calendar; an existing session is written to **its own** calendar, which may
not be the default. Editing a session must not silently move it between calendars — that would
break the mirror on both sides at once.

### Admin UI

A calendars page under `/admin`: list the configured calendars with their last sync time, add
one by Google calendar id with a display name, set which is default, and enable or disable
syncing. No deletion in this version — disabling is the reversible operation, and deletion would
have to decide the fate of the events pointing at it.

**Assumption, flag if wrong:** every *enabled* calendar is synced, not only the default. "Add a
new one" reads as wanting two live calendars (a personal and a club one, say) rather than only
ever migrating between them. Syncing only the default would let a disabled-by-omission calendar
drift silently.

## Architecture

### `GoogleCalendarClient.listChanges(calendarId: String, syncToken: String?): CalendarChangeSet`

A fourth method; the existing three gain a `calendarId` parameter. Google's `Event` type stays
inside `GoogleCalendarClientImpl`, as it already does for writes, so nothing downstream imports
the Google SDK.

```kotlin
sealed class CalendarChange {
    abstract val googleEventId: String

    data class Upserted(
        override val googleEventId: String,
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val description: String?
    ) : CalendarChange()

    data class Cancelled(override val googleEventId: String) : CalendarChange()
}

data class CalendarChangeSet(
    val changes: List<CalendarChange>,
    /** Null when a full resync is required; the caller must clear its stored token. */
    val nextSyncToken: String?,
    val fullResyncRequired: Boolean,
    /** True only for a completed full sync: the window below was fetched in full. */
    val isCompleteWindow: Boolean,
    /** The window a full sync covered, for scoping deletions. Null on an incremental sync. */
    val windowStart: LocalDateTime?
)
```

Listing passes `singleEvents = true`, so a recurring event created in Google arrives as
individual instances rather than a master with a recurrence rule — matching how DanceBook models
its own series.

Errors translate through the existing `translating(...)` helper into `CalendarSyncException`,
with one exception: a `410` is a signal, not an error, and returns `fullResyncRequired = true`.

### `CalendarReconciler`

A `@Component` that applies one calendar's `CalendarChangeSet`. It knows nothing about when it
runs.

It writes through **`TrainingEventPersistence`**, never `TrainingEventServiceImpl`. This is
load-bearing twice over. `TrainingEventServiceImpl` writes back to Google on every mutation, so
routing inbound changes through it would echo each change straight back. And
`TrainingEventPersistence` is where `TrainingRecordWriter` is wired, so an event deleted in
Google's UI *orphans* its training record instead of erasing the hours — the guarantee from
issue #49, extended to Google's own UI for free.

### `CalendarSyncService`

Per-calendar orchestration: for each enabled calendar, read its token, call the client, hand the
changeset to the reconciler, store the next token. Owns throttling and the single-flight guard.

### Trigger

A `HandlerInterceptor` registered in `WebMvcConfig` for `GET /training-events/**`, calling
`syncIfDue()` before the handler runs — one registration rather than a call bolted separately
into the calendar, agenda and timeline controllers. Plus `POST /training-events/sync` behind a
"Sync now" control.

## Sync rules

1. **Google owns title, start and end.** Nothing else. Attendance, event type and style
   breakdown are never touched by inbound sync.

2. **Descriptions do not sync inbound for known events.** `buildCalendarDescription` folds
   `Type:`, `Styles:` and `Materials:` lines into the Google description; copying it back would
   fold the app's metadata into the user's description and accumulate it every round-trip.
   Adopted events take their description as-is, because the app never wrote it — and since known
   events never read it back, nothing accumulates afterwards.

3. **No-op changes are skipped entirely.** Every app write returns in the next changeset. When
   title, start and end all match what is stored, do nothing: no save, no `updated_at` bump, no
   domain event, no record re-sync. Echo suppression without change-tracking ids, and it keeps
   the activity feed free of reflections of the user's own edits.

4. **Deletions are applied.** A `Cancelled` for a known event routes to
   `TrainingEventPersistence.remove`, which orphans its training record so the hours survive. A
   `Cancelled` for an unknown id is ignored. The reconciler never calls
   `calendarClient.deleteEvent` — the event is already gone.

5. **A full resync infers deletions only inside the window it actually fetched.** On
   `fullResyncRequired`, clear that calendar's token and re-list from one year back. A local
   event is deleted only when *all* of these hold: it belongs to the calendar being synced, it
   has a `google_event_id`, its start falls inside the fetched window, the window was fetched
   completely (`isCompleteWindow`), and Google did not return it.

   Each condition removes a way to destroy real data. Scoping to the calendar is what makes
   adding a calendar safe. Scoping to the window stops events older than a year — which were
   never asked about — from being deleted for being absent. Requiring a complete fetch stops a
   pagination failure, which looks exactly like mass deletion, from being acted on.

   A grace period excludes rows created or updated in the last minute, so a session written to
   Google moments earlier cannot be deleted before Google's list reflects it.

6. **A shortened session trims its style breakdown.** If an inbound time change leaves the
   session shorter than the sum of its segments, whole segments are dropped from the end until
   the breakdown fits, and the drop is logged. Otherwise style totals would exceed the hours
   card and break the reconciliation invariant `TrainingStatsServiceTest` asserts. The remainder
   lands in "Unassigned" as usual.

7. **Adoption.** An `Upserted` with an unknown `googleEventId` becomes a new `TrainingEvent` on
   the calendar being synced: `TRAINING`, `PLANNED`, no segments, description as-is, owned by the
   root admin. It is not written back to Google.

8. **Rows with no `google_event_id` are invisible to sync.** Those are creates whose calendar
   write failed mid-flight; sync neither matches nor deletes them.

9. **All-day events map midnight to midnight**, accounting for Google's exclusive end date.
   Without explicit handling the first all-day entry crashes the mapping.

10. **A series occurrence is updated alone.** An inbound change updates that row and leaves
    `series_id` and the `TrainingSeries` definition untouched. The series is a generation
    template, not a live constraint.

11. **Throttle and single-flight.** At most one sync run per
    `calendar_sync_interval_seconds` (a `system_setting` key, default `60`), plus an in-process
    guard so concurrent requests do not double-sync. The throttle matters because HTMX fragment
    requests hit these paths often. "Sync now" bypasses the throttle but not the guard.

## Why the history table is not an audit log

`training_record` holds one row per session, updated in place. That was a deliberate decision in
issue #49 and it is what lets the statistics service sum rows directly, with no
latest-row-per-session windowing. Turning it into an append-only stream of calendar actions
would force every statistic to fold that stream to reconstruct current state first —
substantially more complex code producing identical figures.

The audit log already exists. `activity_event` records `TRAINING_EVENT_CREATED`, `UPDATED` and
`DELETED` with an actor, a target held by plain id, and a timestamp, and is built to outlive its
target. A richer history of calendar actions belongs there, and surfacing it is a UI change
rather than a new table.

The division: **`training_record` answers "what training happened"** and feeds the dashboard.
**`activity_event` answers "who did what, when".**

Inbound sync should publish activity events for the changes it applies, attributed to the root
admin, so the feed records what Google's UI did rather than silently diverging from it.

## Per-user attribution

Both tables are already per-user: `training_record.created_by_id` and `activity_event.actor_id`
both restrict rather than cascade. Keep that; nothing changes.

The real limit is not the tables but the calendars. A `training_calendar` is app-level, shared by
everyone, so an event created in Google's UI has no user to attribute it to and lands on the root
admin. Per-user attribution is therefore only meaningful for app-originated actions until
calendars themselves become per-user — which is out of scope here, and worth recording so it is
not mistaken for a bug later.

## Failure handling

**Sync never breaks a page.** The interceptor wraps `syncIfDue()` in a catch-all: log and render
anyway, with data that may be stale. A Google outage must not take down pages that worked
one-directionally before this feature existed.

**One calendar's failure does not stop the others.** Each enabled calendar syncs independently;
a failure is logged against that calendar and the run continues.

**The token advances only after the whole changeset applies.** If the reconciler throws partway
through, the token is not stored, so Google resends those changes. Google does not resend once a
token is acknowledged, so storing it early loses changes permanently. The cost is that
already-applied changes reappear on retry, which is free because rule 3 skips them: idempotent
reconciliation is what makes at-least-once delivery harmless. Pagination follows the same rule —
only the final page carries `nextSyncToken`, so every page must be drained first.

**Concurrency is handled by the database, not the guard.** The single-flight lock is per-instance
and Cloud Run may run several. Two instances syncing at once apply the same changes; the
second's writes are no-ops. The one real race — both adopting the same new event — is caught by
`google_event_id`'s unique constraint, which rolls the transaction back so the token is not
stored and the work retries. The guard is an optimisation; the constraint is the correctness.

**No enabled calendar is a no-op.** Before bootstrap, or with `GOOGLE_CALENDAR_ID` unset, sync
does nothing rather than erroring on every page load.

**"Sync now" reports failures.** It is a deliberate action, so unlike the background path it
surfaces errors instead of swallowing them.

## Schema

This needs a Flyway migration — `V29__add_training_calendar.sql` if the training-record work
lands first.

- `training_calendar` as described above, `google_calendar_id` unique.
- `training_event.calendar_id UUID REFERENCES training_calendar(id)`, nullable at first and
  filled by the startup bootstrap, indexed because every sync scopes by it.
- No seeding in SQL: the first row comes from `GOOGLE_CALENDAR_ID` at boot.

`calendar_sync_interval_seconds` needs no migration — `SystemSettingService.updateSetting`
upserts and the getter takes a default.

## Testing

Unit tests carry the rules: the reconciler (adopt, update, skip no-op, cancel a known event
through `TrainingEventPersistence`, ignore an unknown cancellation, trim an overlong breakdown,
ignore rows with no Google id, never touch another calendar's events); the client's change
mapping (timed events, all-day events with their exclusive end date, `cancelled` status, `410`
surfacing as `fullResyncRequired`); and the sync service (token stored on full success and never
on failure, throttle, single-flight, one calendar's failure not stopping the next).

Rule 5 deserves its own cluster, since every condition exists to prevent a specific way of
destroying data: an event outside the window is not deleted; an incomplete fetch deletes nothing;
another calendar's events are never considered; a just-created event inside the grace period
survives.

Integration tests on real Postgres with a fake `GoogleCalendarClient`:

- **The one that matters most:** mark a session Attended, feed an inbound cancellation, and
  assert the `training_event` row is gone while the `training_record` survives — orphaned, hours
  intact. This proves the two features compose rather than merely coexist, and it is exactly the
  scenario issue #49 exists to protect.
- Adding a second calendar and syncing it adopts its events and leaves the first calendar's
  events untouched — the safety property the whole multi-calendar design exists to provide.
- An inbound time change on an attended session updates the record's `occurredAt` and
  `durationMinutes`, consistent with edits following a record while its session lives.
- The bootstrap creates a calendar from `GOOGLE_CALENDAR_ID` and backfills existing events to it
  exactly once, and is idempotent across restarts.

## Suggested phasing

This is now two coherent pieces, and each produces working software on its own:

**Phase 1 — calendars as entities.** The table, the `calendar_id` column, the bootstrap and
backfill, calendar id threaded through the three write methods, and the admin UI. Ships value
alone: you can add a calendar and move where new sessions are created, safely, with no inbound
sync yet.

**Phase 2 — inbound sync.** `listChanges`, the reconciler, the sync service, the interceptor and
"Sync now".

Phase 1 is the riskier half, because it migrates existing data and touches every write path.
Phase 2 is additive and cannot corrupt anything that phase 1 did not already get right.

## Out of scope

- **Push notifications (`events.watch`).** Deferred; adding it later is a new caller of
  `CalendarSyncService`, not a rewrite.
- **Deleting a calendar.** Disabling is the reversible operation; deletion would have to decide
  the fate of the events pointing at it.
- **Per-user calendars.** Calendars stay app-level, so inbound events are attributed to the root
  admin.
- **Filtering statistics by calendar.** `training_record` does not record which calendar a
  session came from; add it only if the need appears.
- **Syncing descriptions inbound for known events.** Rule 2 explains why; revisit only with a
  round-trip-safe encoding of the metadata block.

## Acceptance criteria

- [ ] A time or title change made in Google's UI appears in DanceBook on the next visit to a
      training page
- [ ] An event deleted in Google's UI removes the local session, and its training record survives
      intact and marked orphaned
- [ ] An event created in Google's UI appears as a `TRAINING`/`PLANNED` session with no style
      breakdown, on the calendar it came from
- [ ] An app-originated change returning in the next changeset produces no database write and no
      activity-feed entry
- [ ] An expired sync token triggers a full resync that deletes only events inside the fetched
      window, on that calendar, and only when every page was fetched
- [ ] An incomplete or failed fetch deletes nothing
- [ ] Adding a second calendar syncs it without modifying or deleting any event belonging to
      another calendar
- [ ] A new session is created in the default calendar; editing an existing session writes to its
      own calendar and never moves it
- [ ] On first boot after the migration, a calendar is created from `GOOGLE_CALENDAR_ID` and every
      existing event is backfilled to it; restarting does not duplicate it
- [ ] An inbound change shortening a session below its style total trims the breakdown rather than
      breaking the stats reconciliation invariant
- [ ] A Google API failure during sync leaves every training page rendering normally, and a
      failure on one calendar does not stop the others
- [ ] A failure partway through a changeset leaves that calendar's token unchanged, so the changes
      arrive again next sync
- [ ] Attendance, event type and style breakdown are never modified by inbound sync
