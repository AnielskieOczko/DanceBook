# Calendar work — where things stand

Last updated 2026-09-13, after the calendar view-context plan merged.

## Done and on `main`

| PR | What |
| --- | --- |
| #57 | #52 + #58 — calendars as first-class entities, Google-id verification, admin add/default/enable |
| #59 | The design spec and both implementation plans |
| #60 | Plan A — calendar as a view context: active-calendar session state, the selector, all five training views scoped, V30 record denormalisation, per-session picker removed |

Highest migration is **V30**. The app has an active-calendar context in the session, a selector
in each training page header, and per-calendar statistics, history, timeline, agenda and grid.

## Next

**Plan B — `2026-09-13-calendar-admin-crud.md`.** Admin edit and delete for calendars, plus the
shared server-rendered confirm dialog. Independent of everything above; its dependencies are all
on `main` already. Start by reading that plan's "What executing plan A taught" section — it
carries the mistakes plan A paid for.

Design authority for both plans is
`docs/superpowers/specs/2026-09-13-calendar-as-view-context-design.md`.

## Open follow-ups

- **#61** — V30's backfill depends on V29's startup runner having already run. Matters for DR
  restores and staging refreshes, not for normal deploys. The only item here with a data
  consequence.
- **#62** — test coverage gaps. One of them, the deleted-calendar fallback in
  `ActiveCalendarService.active()`, becomes reachable the moment plan B's delete ships, so it is
  worth closing as part of plan B rather than afterwards.
- **#63** — `calendarIdsInUse()` has no owner filter. Inert at two users.

## Known behaviours, decided deliberately

- Selecting a calendar shows **smaller totals** than before; only "All calendars" reproduces the
  old figures.
- Records orphaned before V30 have no recoverable calendar and appear only under "All calendars".
- The active calendar lives in the HTTP session, so it resets on logout and is **shared across
  browser tabs** — changing it in one tab changes what another fetches next. Accepted rather than
  moved to the URL.
- Editing a session cannot move it between calendars; that needs Google's `events.move()`.

## Still unbuilt

**#53, inbound sync.** Nothing reads back from Google, so an event created or renamed in Google's
own UI is still invisible to the app. `GoogleCalendarClient` has `createEvent`, `updateEvent`,
`deleteEvent` and `verifyCalendar` — no list operation. Plan A gave it the per-calendar sync
tokens it will need.
