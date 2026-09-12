# Spec: Training calendar — record training history separately from calendar events

Source: GitHub issue [#49](https://github.com/AnielskieOczko/DanceBook/issues/49), part of #35.
Captured verbatim below so the plan and its spec travel together.

---

Part of #35

Should land before #47 and #48 — both delete sessions in bulk, and until history is stored
separately every such delete silently rewrites the statistics.

Statistics read `TrainingEvent` rows, which are also the calendar schedule: write-through mirrored to
Google Calendar, freely editable, freely deletable. A schedule entry is meant to be disposable — you
tidy a cluttered calendar, you point the app at a new Google calendar, you throw away a series you
created by mistake. A training record should be permanent. Today those are the same row, so tidying
the calendar silently rewrites your history: delete a skipped session and your attendance rate
improves; delete a month of training and the hours never happened.

Record confirmed training in its own table, and make that table the source of truth for statistics.

This refines #35's "local DB is the source of truth" decision rather than replacing it: the DB stays
authoritative, but the *schedule* and the *history* become separate concerns with separate lifecycles,
because only one of them is disposable.

## What gets recorded

A record is written when a session is marked **Attended** or **Skipped** — a confirmed outcome.
Sessions that were never confirmed are not history; unconfirmed means unknown. Each record captures
what statistics need, snapshotted so it stands alone once its session is gone:

- the date and time the session happened, and its length in minutes
- the outcome, attended or skipped
- the session title and event type
- the style breakdown — which dance category, how many minutes, in order

Marking a session back to Planned or Cancelled removes its record: that is a correction of a
mis-mark, not history.

## Decisions

**A record survives the deletion of its session.** This is the entire point. Deleting a session marks
its record as orphaned rather than removing it — a deliberate copy of how `activity_event` holds its
target by plain id with no foreign key, so the row outlives what it describes. The record also keeps
its own copies of the title, duration and category names, so an orphaned record still reads correctly.

**Records follow edits while linked, then freeze.** While the session still exists, editing it updates
its record too — fixing a wrong duration fixes your statistics. Once the session is deleted, the
record is frozen for good.

**One record per session, updated in place.** Changing your mind from Attended to Skipped updates the
record rather than appending a correction, so statistics need no latest-row-per-session windowing.

**Records are written in the same transaction as the session.** The activity feed is written from an
after-commit listener, which can afford to lose a row; statistics cannot. Record writes belong in the
transactional persistence beans alongside the session write, not in a listener.

**Statistics become deliberately hybrid.** Hours, attended and skipped counts, attendance rate, streak
and both breakdowns come from records. Upcoming, unconfirmed and cancelled counts stay on calendar
events — those are facts about the *schedule*, not about history, and they have no business outliving
the sessions they describe.

**The record's owner reference must not cascade.** A session's `created_by_id` cascades on user delete;
a record must follow the `activity_event` convention of restricting instead, or deleting a user would
take the history with it.

**Style breakdowns group by category id and label from the live category**, falling back to the
snapshotted name when the category no longer exists. This also fixes a latent bug: statistics
currently key the breakdown on the category *name*, so renaming a category retroactively relabels and
merges historical slices — which has already happened once in this codebase.

## Statistics that get simpler

The streak currently has to step over cancelled and unconfirmed sessions to find consecutive attended
ones. Records contain only attended and skipped outcomes, so the streak becomes exactly what it claims
to be: consecutive attended sessions back to the first skip.

Session duration is currently derived from the start/end timestamps, so rescheduling an
already-attended session quietly changes historical hours. A recorded duration cannot drift that way.

## History view

Records outlive their sessions, so they need somewhere to be seen. Add a history view listing
confirmed training newest-first, grouped by month with per-month totals, showing each session's
outcome, length and style breakdown. Orphaned records — the ones whose calendar session is gone — must
be visibly marked as such, and removable, so a session marked by mistake can still be corrected after
its event has been deleted.

## Existing data

Sessions already marked Attended or Skipped must be backfilled into records, with their style
breakdowns, as part of the migration. Anything already deleted is gone and cannot be recovered.

## Relationship to #47 and #48

Both of those delete sessions in bulk — #47 by selection, #48 across a whole series. As specified,
each of those deletes destroys statistics. This issue makes them safe, which is why it should land
first.

It also removes the reason behind one of #48's decisions: that "all events" delete must keep
occurrences that already happened. That rule exists only to avoid losing recorded attendance. Once
attendance lives in its own table, a series can be deleted in full and the history stands.

## Out of scope

Re-pointing the app at a different Google calendar still leaves every stored Google event id dangling,
and nothing reconciles the two directions. This issue does not fix that — it makes the training
history survive it.

## Acceptance criteria

- [ ] Marking a session Attended or Skipped writes a record; un-marking it removes the record
- [ ] Editing a session's time, duration, type or style breakdown updates its record while the session
      exists
- [ ] Deleting a session — singly, in bulk, or across a series — leaves its record intact and marks it
      orphaned
- [ ] An orphaned record keeps a readable title, duration and style breakdown of its own
- [ ] Deleting a dance category does not destroy records that referenced it
- [ ] Deleting a user does not silently destroy their records
- [ ] Hours, attended/skipped counts, attendance rate, streak and both breakdowns are computed from
      records; upcoming, unconfirmed and cancelled counts still come from calendar events
- [ ] Style breakdown slices group by category and survive a category rename without relabelling
      history
- [ ] The stats page's reconciliation invariant still holds — total hours equals the sum of the
      event-type breakdown, with the same Unassigned remainder behaviour
- [ ] A history view lists confirmed training by month, marks orphaned records, and allows removing a
      mis-marked one
- [ ] Existing Attended and Skipped sessions are backfilled into records by the migration
- [ ] Record writes commit atomically with the session write, not through an after-commit listener

## Notes

- Needs a Flyway migration (`ddl-auto=validate`); next free number is V28 at time of writing.
- Follow `activity_event`'s conventions for a row that outlives its subject: the session reference held
  as a plain id with no foreign key, a denormalised name snapshot, and an owner reference that
  restricts rather than cascades. Follow `storage_cleanup_log` for column-level immutability.
- The session-edit form carries `attendanceStatus`, so a plain edit is an attendance-change path too,
  not just the one-tap confirm buttons. Both must write records.
- The two series bulk methods publish no domain event today, so bulk series changes need explicit
  record handling rather than riding on an existing event.
- The stats service is the only consumer of its repository read, so the computation swap is contained —
  but `TrainingStatsServiceTest` asserts 18 behaviours including the reconciliation invariant, and
  those assertions should keep passing against records.

## Blocked by

None - can start immediately, and should start before #47 and #48.

