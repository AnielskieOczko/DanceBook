# Google Calendar inbound sync — design

**Status:** approved in brainstorming, ready for an implementation plan
**Depends on:** the training-record work on `feature/training-records` (PR #50), which this
design writes through and relies on for history safety.

## Problem

The calendar integration is write-through only. Every mutation in DanceBook goes to Google
Calendar and then to the local database, but nothing ever reads back:
`GoogleCalendarClient` exposes `createEvent`, `updateEvent` and `deleteEvent` and no list
operation at all. Google has no idea the app exists.

So an edit made in Google's own UI — moving a session, renaming it, deleting it, or adding a
new one on the phone — is invisible to DanceBook forever. The app and the calendar drift
apart silently, and the app is the only one of the two that can be corrected.

## Decisions

These were settled during brainstorming and bound the design.

**Inbound sync covers new Google events too, not only ones the app created.** Anything
appearing on the dedicated training calendar becomes a DanceBook session. The alternative —
tracking only events that already carry a `google_event_id` — was rejected because creating a
session on a phone in Google's UI is exactly the case worth supporting.

**Google wins on conflict.** When the same session changed on both sides, the inbound change
overwrites the local row. This matches how the calendar is actually used: whichever UI is to
hand gets the edit, and the last edit should stick.

That rule reaches less far than it sounds. Google has fields for a title, a start and an end,
and nothing else. Attendance, event type and style breakdown are app-only concepts, so they
are not contestable — there is nothing inbound that could overwrite them.

**An adopted event is a plain `TRAINING` session.** Type `TRAINING`, status `PLANNED`, no
style breakdown, owned by the root admin. It behaves exactly like a session created in the app
and not yet detailed: once attended, its hours land in the existing "Unassigned" slice until
styles are filled in. No new "needs details" state is introduced.

**The reconciler is built first and the trigger is chosen separately.** Translating a
changeset into local writes is identical whether the changeset arrived by polling, by push
notification or on a page view. The trigger is the cheap, swappable part.

**Sync on view is the first trigger.** A scheduled poller was rejected because the Cloud Run
deployment sets no `--min-instances`, so the service scales to zero and `@Scheduled` work
cannot fire reliably. (The existing `StorageCleanupJob` almost certainly has this problem
already — worth its own issue.) Push notifications via `events.watch` were rejected for now as
the most moving parts: a public unauthenticated endpoint, channel registration and renewal,
token verification — and the renewal job hits the same scale-to-zero problem. Adding `watch`
later is a new caller of the same reconciler, not a rewrite.

## Architecture

Three components and one trigger.

### `GoogleCalendarClient.listChanges(syncToken: String?): CalendarChangeSet`

A fourth method on the existing interface. Google's `Event` type stays inside
`GoogleCalendarClientImpl`, exactly as it already does for the three write methods, so nothing
downstream imports the Google SDK.

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
    val fullResyncRequired: Boolean
)
```

Listing passes `singleEvents = true`, so a recurring event created in Google arrives as
individual instances rather than a master with a recurrence rule. That matches how DanceBook
models its own series: independent occurrences, each with its own attendance.

Errors are translated through the existing `translating(...)` helper into `CalendarSyncException`,
with one exception: a `410` is not an error but a signal, and is returned as
`fullResyncRequired = true`.

### `CalendarReconciler`

A `@Component` that applies a `CalendarChangeSet`. It knows nothing about when it runs.

It writes through **`TrainingEventPersistence`**, never `TrainingEventServiceImpl`. This is
load-bearing in two directions. `TrainingEventServiceImpl` writes back to Google on every
mutation, so routing inbound changes through it would echo each change straight back and risk
a loop. And `TrainingEventPersistence` is where `TrainingRecordWriter` is wired, so an event
deleted in Google's UI *orphans* its training record rather than erasing the hours — the
guarantee issue #49 exists to provide, obtained here for free.

### `CalendarSyncService`

Orchestration: read the stored token, call the client, hand the changeset to the reconciler,
store the next token, handle the full-resync path. Owns throttling and the single-flight guard.

### Trigger

A `HandlerInterceptor` registered in `WebMvcConfig` for `GET /training-events/**`, calling
`syncIfDue()` before the handler runs — one registration rather than a call bolted separately
into the calendar, agenda and timeline controllers. Plus `POST /training-events/sync` behind a
"Sync now" control for when freshness is wanted immediately.

## Sync rules

1. **Google owns title, start and end.** Nothing else. Attendance, event type and style
   breakdown are never touched by inbound sync.

2. **Descriptions do not sync inbound for known events.** `buildCalendarDescription` folds
   `Type:`, `Styles:` and `Materials:` lines into the Google description. Copying that back
   would fold the app's own metadata into the user's description and accumulate it on every
   round-trip. Adopted events *do* take their description as-is, because the app never wrote
   it; since known events never read it back, nothing accumulates afterwards.

3. **No-op changes are skipped entirely.** Every app write to Google returns in the next
   changeset. When title, start and end all match what is stored, do nothing — no save, no
   `updated_at` bump, no domain event, no record re-sync. This is echo suppression without
   change-tracking ids, and it keeps the activity feed from filling with reflections of the
   user's own edits.

4. **Deletions only ever come from an incremental changeset.** A `Cancelled` for a known event
   routes to `TrainingEventPersistence.remove`, which orphans its training record. A
   `Cancelled` for an unknown id is ignored. The reconciler never calls
   `calendarClient.deleteEvent`: the event is already gone.

5. **A full resync never deletes.** On `fullResyncRequired`, clear the token and re-list with
   `timeMin` set to exactly one year before now, treating every result as an upsert only. A windowed full sync
   cannot distinguish "deleted in Google" from "older than the window", so inferring deletions
   there would delete real schedule rows and orphan their records in bulk. This is the most
   dangerous operation in the feature and this rule is what defuses it.

6. **A shortened session trims its style breakdown.** If an inbound time change leaves the
   session shorter than the sum of its segments, whole segments are dropped from the end until
   the breakdown fits, and the drop is logged. Without this the style totals would exceed the
   hours card and break the reconciliation invariant `TrainingStatsServiceTest` asserts. The
   remainder lands in "Unassigned" as usual.

7. **Adoption.** An `Upserted` with an unknown `googleEventId` becomes a new `TrainingEvent`:
   `TRAINING`, `PLANNED`, no segments, `google_event_id` set, description taken as-is, owned by
   the root admin (`dancebook.security.root-admin`). It is not written back to Google.

8. **Rows with no `google_event_id` are invisible to sync.** Those are creates whose calendar
   write failed mid-flight. Sync neither matches nor deletes them.

9. **All-day events map midnight to midnight.** Google supplies `start.date` rather than
   `start.dateTime`, and its end date is exclusive. Without explicit handling the first all-day
   entry on the calendar crashes the mapping.

10. **A series occurrence is updated alone.** An inbound change to an occurrence updates that
    row and leaves `series_id` and the `TrainingSeries` definition untouched. The series is a
    generation template, not a live constraint.

11. **Throttle and single-flight.** At most one sync per
    `calendar_sync_interval_seconds` (a `system_setting` key, default `60`, so it is tunable
    without a deploy), plus an in-process guard so concurrent requests do not double-sync. The
    throttle matters because HTMX fragment requests hit these paths often. An explicit
    "Sync now" bypasses the throttle but not the guard.

## Failure handling

**Sync never breaks a page.** The interceptor wraps `syncIfDue()` in a catch-all: log and
render anyway, with data that may be stale. A Google outage must not take down the agenda,
calendar and timeline, all of which worked one-directionally before this feature existed.

**The token advances only after the whole changeset applies.** If the reconciler throws partway
through, the token is not stored, so Google resends those changes next time. Google does not
resend once a token is acknowledged, so storing it early loses changes permanently. The cost of
the safe order is that already-applied changes reappear on retry, which is free because rule 3
skips them. Idempotent reconciliation is what makes at-least-once delivery harmless.

**Pagination follows the same rule.** A large changeset returns `nextPageToken` until the final
page, which alone carries `nextSyncToken`. Every page must be drained before the token is
stored.

**Concurrency is handled by the database, not the guard.** The single-flight lock is
per-instance and Cloud Run may run several. Two instances syncing at once apply the same
changes; the second's writes are no-ops. The one real race — both adopting the same new event —
is caught by `google_event_id`'s unique constraint, which rolls the transaction back so the
token is not stored and the work retries. The in-process guard is an optimisation; the
constraint is the correctness.

**An unconfigured calendar stays a no-op.** `GOOGLE_CALENDAR_REFRESH_TOKEN` and
`GOOGLE_CALENDAR_ID` already default to empty and the client guards on that, so sync does
nothing rather than erroring on every page load.

**The explicit "Sync now" control reports failures.** It is a deliberate user action, so unlike
the background path it surfaces an error rather than swallowing it.

## Schema

**None.** No Flyway migration is required for this feature.

The sync token is stored in `system_setting` under the key `google_calendar_sync_token`. That
table's `updateSetting` already upserts, so the key needs no seeding, and `setting_value` is
`VARCHAR(1024)` — comfortably larger than a Google sync token. The token must survive a restart,
or every deploy would force a full resync.

The last-sync timestamp is deliberately *not* persisted: it lives in memory on each instance.
A restart simply allows one extra sync, which is a no-op under rule 3, and keeping it out of the
database avoids a write on every page view. `calendar_sync_interval_seconds` is the only other
setting, and it is read, never written, by this feature.

## Testing

Unit tests carry the rules:

- **`CalendarReconciler`** — adopt an unknown event; update a known one; skip a no-op; cancel a
  known event through `TrainingEventPersistence`; ignore an unknown cancellation; trim an
  overlong breakdown; ignore rows with no Google id.
- **Change mapping in `GoogleCalendarClientImpl`** — timed events, all-day events including the
  exclusive end date, `cancelled` status, and a `410` surfacing as `fullResyncRequired` rather
  than an exception.
- **`CalendarSyncService`** — the token is stored on full success and never on failure; the
  full-resync path is upsert-only; the throttle skips a call inside the window; single-flight
  holds.

Integration tests on real Postgres with a fake `GoogleCalendarClient`:

- **The one that matters most:** mark a session Attended, feed an inbound cancellation, and
  assert the `training_event` row is gone while the `training_record` survives — orphaned, with
  its hours intact. This proves the two features compose rather than merely coexist, and it is
  precisely the scenario issue #49 exists to protect.
- An inbound time change on an attended session updates the record's `occurredAt` and
  `durationMinutes`, consistent with the decision that edits follow a record while its session
  lives.
- An adoption lands as a root-admin-owned `TRAINING`/`PLANNED` session with no segments.

## Out of scope

- **Push notifications (`events.watch`).** Deliberately deferred; adding it later is a new
  caller of `CalendarSyncService`, not a rewrite.
- **Re-pointing the app at a different Google calendar.** Every stored `google_event_id` would
  dangle, and nothing reconciles that. Issue #49 named this out of scope and it stays so; note
  that a full resync under rule 5 will adopt the new calendar's events rather than deleting the
  old ones, which is survivable but messy.
- **Editing the repeat horizon when applying a change to a whole series.** Unrelated to sync.
- **Syncing descriptions inbound for known events.** Rule 2 explains why; revisit only with a
  round-trip-safe encoding of the metadata block.

## Acceptance criteria

- [ ] A time or title change made in Google's UI appears in DanceBook on the next visit to a
      training page
- [ ] An event deleted in Google's UI removes the local session and leaves its training record
      intact and marked orphaned
- [ ] An event created in Google's UI appears as a `TRAINING`/`PLANNED` session with no style
      breakdown, owned by the root admin
- [ ] An app-originated change returning in the next changeset produces no database write, no
      domain event and no activity-feed entry
- [ ] An expired sync token triggers a full resync that adds and updates but never deletes
- [ ] An inbound change shortening a session below its style total trims the breakdown rather
      than breaking the stats reconciliation invariant
- [ ] A Google API failure during sync leaves every training page rendering normally
- [ ] A failure partway through a changeset leaves the stored token unchanged, so the changes
      arrive again on the next sync
- [ ] With no calendar configured, sync is a no-op and no page reports an error
- [ ] Attendance, event type and style breakdown are never modified by inbound sync
