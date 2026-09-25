# Access control: private items and calendar subscriptions — epic design

**Status:** approved in brainstorming as an epic. Each sub-issue gets its own implementation plan.
**Must land before:** RAG (`2026-09-25-rag-design.md`), so the index stores visibility from the
start.
**The assistant** (`2026-09-25-ai-assistant-design.md`) reaches data only through services, so
it inherits these rules without changes.

## Problem

DanceBook started out as one person's tool, and its data model still assumes that:

- **Notes (`Material`) have no owner.** Every user sees and edits every note.
- **Collections and choreographies** have `owner` + `isPublic`, but that is the only place the
  pattern is used.
- **Attendance is one field per session**, not per person. `TrainingEvent.attendanceStatus` is
  a single value, and `training_record.training_event_id` is `unique`. Stats look per-user only
  because they filter by who *created* the session (`TrainingStatsServiceImpl`). Two people
  sharing a session would share one attendance.
- **Calendars are global.** `TrainingCalendar` has no owner, `isDefault` is one flag for
  everyone, and all Google access runs through one app-level account (`GOOGLE_REFRESH_TOKEN`).

## Target rules

**Private by default.** An item the user creates is visible only to them until they publish it.

| Item | Visibility |
|---|---|
| Note, comment on a private note, collection, choreography | `PRIVATE` by default, can be set to `PUBLIC` |
| Training calendar | `PRIVATE` by default, can be set to `PUBLIC`, and has members (below) |
| **Dance figure**, syllabus ones included | **Always public, and any user can edit it.** There is no visibility setting (see below). |
| Dance styles, categories | System reference data, always visible, and only the admin edits them |
| Assistant conversations | Always private to their owner (assistant spec) |

**Figures are always public.** Anyone can create a figure, and every user can see it and use it
in notes and choreographies. Making figures private would let one user break another user's
choreography by hiding a figure it uses. The create form says "Figures are shared with every
DanceBook user."

**Figures are edited by the community.** Any user can edit any figure, including the imported
syllabus figures: improving the catalog is a contribution, like editing a wiki. Deleting is
narrower:
- the creator or an admin can delete a figure
- **deleting is refused while another user's note or choreography uses it**
- **syllabus figures can only be deleted by an admin**

Accidental or bad edits are traceable because every edit already publishes
`DANCE_FIGURE_UPDATED` with its actor. Concurrent edits use optimistic locking (a new `version`
column on `dance_figure`, as `Material` already has), so a second save shows a conflict instead of silently overwriting the
first. Per-field revision history with restore is a later issue.

**A single access rule decides visibility.** Each entity with visibility gets a JPA
`Specification` for "visible to this user":
- owner = user, or
- visibility = `PUBLIC`, or
- a row in the share table grants it.

Every repository query that serves a user goes through that Specification. So do the lists,
detail pages, HTMX fragments, `/api/**`, search, the dashboard, the activity feed and
notifications, the assistant's tools, and the RAG index. Hidden items return **404**, never
403.

**Sharing later, modelled now.** `share(item_type, item_id, grantee_user_id, permission)` exists
from the first sub-issue, even though v1 grants only `VIEW` through calendar membership.
"Share with one person or a group" then means adding rows and a UI, not changing the access
rule.

## Edge cases and decisions

1. **Existing rows get owners from the activity feed.** `MATERIAL_CREATED` (and the list,
   choreography and training events) record the actor. Rows with no event go to the admin.
   **Existing notes, collections and choreographies become `PUBLIC`**, so nothing disappears
   for anyone on deploy, and owners can make them private afterwards.
2. **Linked items with mixed visibility.** A public note may pin a figure (always visible,
   fine). A public choreography or collection may contain a *note* that is private. Linked
   items are filtered per viewer, so a hidden linked item isn't shown and its slot says "1
   private item". Publishing a collection or choreography warns when it contains private notes.
3. **Making an item private again.** Other users' comments on it are kept but hidden with the
   item. It drops out of other users' collections through rule 2.
4. **The activity feed and notifications** store `targetName`, so they would leak titles. Both
   are filtered through the access rule when read. An event about an item the viewer can't see
   is not shown.
5. **Files.** Drive-backed videos and uploaded images follow their note. The file-serving
   endpoint checks the access rule rather than serving by filename alone.
6. **Admin** can see everything, for support. Access is logged at debug level.

## Calendars and subscriptions

**Model:**
- `training_calendar` gains `owner_id`, `visibility`, `color`.
- `calendar_source`: the Google calendars linked to a DanceBook calendar, one calendar to many.
  **Exactly one source is the write target** (`is_write_target`), and the others are inbound
  only. It replaces today's one-to-one `google_calendar_id`.
- `calendar_member(calendar_id, user_id, role, state)`:
  - `role`: `OWNER`, `VIEWER`, or `EDITOR` later
  - `state`: `INVITED` or `ACTIVE`
  - An **invite** is an `INVITED` row, and a **subscription** to a public calendar is a row
    created directly as `ACTIVE`. `INVITED` rows may carry an `invited_email` for someone who
    hasn't signed up yet, and they are claimed on first login.
- `app_user.default_calendar_id`: the per-user default. It replaces the global `is_default` and
  sets the dashboard's and stats' default scope.

**Per-user attendance (the foundation).**
- `attendance(training_event_id, user_id, status)` replaces `TrainingEvent.attendanceStatus`.
- `training_record` becomes unique on `(training_event_id, user_id)`. Its `created_by` becomes
  the attendee.
- Stats, history, the timeline and the dashboard read the current user's attendance and
  records.
- **Migration:** each existing event's status and record belong to its `created_by`.

**Behaviour:**
- **When a new user opens the app, they see no calendars.** They create one, join through an
  invite, or subscribe from the public calendar list.
- **Linking Google (v1): the user shares their Google calendar with DanceBook's account** in
  Google Calendar's sharing settings, then pastes its calendar id. DanceBook checks it can read
  it (and write it, if it is the write target) through the existing app-level client. **Later
  (a separate issue):** each user connects their own Google account through OAuth with the
  calendar permission.
- **What members can do.** Viewers and subscribers see the sessions, record **their own**
  attendance, and link **their own** notes. They cannot edit, delete or bulk-edit sessions;
  only the owner (and later editors) can.
- **Owner deletes a session or the calendar.** Each member's `training_record` is orphaned and
  kept, as today, so nobody's history shrinks.
- **A public calendar is made private.** Existing subscribers stay as members and keep access.
  It just disappears from the public list, and the owner can remove members.
- **Unsubscribing** keeps history. If it was the default calendar, the default moves to another
  of the user's calendars, or the dashboard shows "Pick a calendar".
- **The same class in two calendars.** Inbound sync removes duplicates by Google `iCalUID` for
  each user's view, so stats don't count it twice.
- **Publishing a calendar with a personal Google source.** Publishing asks for confirmation
  ("every DanceBook user will see all events from these Google calendars").
- **Activity feed.** Members see session activity for calendars they belong to, and nothing
  else.

**Known limits, accepted for now:**
- **Time zones.** Sessions are `LocalDateTime` with no zone. Correct while every user is in one
  zone, and a separate issue if that changes.
- **Sync load on Neon's free tier.** More sources means more polling, and polling keeps the
  database from suspending. Keep the current interval. Google push notifications (`watch`) are
  a later issue.

## Sub-issues, in order

1. **Access rule foundation and private notes.** Owners, visibility and the share table on
   notes, collections, choreographies and comments; the per-entity Specifications; the
   backfill migration; 404s everywhere; the feed, notifications and file filtering; the
   publish toggle and warnings.
2. **Figures are shared and edited by the community.** `created_by` on `dance_figure`
   (backfilled from the feed, and null for syllabus imports). Any user can edit, with
   optimistic locking. Delete is limited to the creator or an admin, is refused while another
   user's item uses the figure, and syllabus figures are admin-only to delete. Adds the notice
   on the create form.
3. **Per-user attendance.** The attendance table and per-attendee records, with stats, history,
   the timeline and the dashboard reading the current user's. Includes the migration.
4. **Calendar ownership and per-user default.** Owner, visibility and colour on calendars, the
   per-user default, "no calendars" on first open, and multi-source with one write target
   (sharing with the app account).
5. **Members: invites and public subscriptions.** The member table, invite by username or
   email, the public calendar list with subscribe and unsubscribe, the member permissions
   above, and the confirmation when publishing.
6. **Later:** per-user Google OAuth, editor role, group sharing, time zones, push sync, figure
   revision history.

Every sub-issue needs a **second-user test** for each endpoint it touches: user B gets a 404 for
user A's private item, and sees it once it is public or shared.
