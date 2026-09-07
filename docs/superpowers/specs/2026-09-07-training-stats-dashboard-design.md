# Training statistics dashboard — design

Issue: [#37](https://github.com/AnielskieOczko/DanceBook/issues/37) — phase 2 of the
training-calendar epic [#35](https://github.com/AnielskieOczko/DanceBook/issues/35).
Depends on phase 1 (#36), which is merged.

A read-only dashboard summarising the signed-in user's training history: how much they
have trained, how reliably they show up, and how their time splits across dance styles
and event types.

## Scope

The issue's "core stats" list, and nothing beyond it. Average session duration,
per-week trends, competition prep time, most active month and per-user comparison stay
deferred, as the issue specifies.

Phase 4 (#39) reuses the streak calculation from this service. Nothing else here is
built for a future phase.

## Decisions

**Personal scope.** Every figure covers the signed-in user's own events, matching
`TrainingEventService.findByCurrentUser` and the calendar. The Google Calendar is shared
by ACL, but attendance and streaks are personal properties.

**Period selector, defaulting to all time.** Three ranges — last 30 days, this year, all
time — chosen with plain links, so the page is a normal GET and the charts initialise
exactly once per load. The alternative, an HTMX fragment swap matching the list page's
filter idiom, would mean destroying and re-creating Chart.js instances on every swap; the
lifecycle bugs are not worth saving a page load on a control used twice a session.

A session belongs to a period when its `startTime` falls in the window. Last 30 days is
`[today - 30 days, ∞)` and this year is the current calendar year *including its
remaining months*, both left-bounded only — so the upcoming-sessions figure stays
meaningful instead of reading zero on every period but all time.

**Unconfirmed sessions are unknown, not absent.** A past session still marked `PLANNED`
(`TrainingEvent.isAwaitingConfirmation`) neither counts against the attendance rate nor
breaks the streak. The dashboard instead shows how many need confirming and links to
them, so the statistics correct themselves as the user tidies their records. Counting
them as skipped would punish forgetfulness rather than absence.

**Computation in Kotlin, not SQL.** One query loads the user's events; the service is a
pure function of that list. The streak is sequential and awkward to express in SQL, and
splitting the logic across two languages would make the phase 4 reuse harder. At a few
hundred sessions a year the aggregate cost is irrelevant. This is a simplicity-over-scale
choice worth revisiting if a user ever accumulates thousands of sessions.

**No schema change.** `spring.jpa.hibernate.ddl-auto=validate` makes migrations
mandatory for entity changes; this feature adds no entity and therefore no Flyway
migration.

## Statistics

Definitions, since each number needs one somebody can check.

| Statistic | Definition |
| --- | --- |
| Hours trained | `durationMinutes` summed over `ATTENDED` sessions in the period |
| Session counts | Five disjoint buckets: upcoming (`PLANNED`, not yet ended), unconfirmed (`isAwaitingConfirmation`), attended, skipped, cancelled |
| Attendance rate | `attended / (attended + skipped)`, whole percent. Cancelled and unconfirmed sessions are excluded; a zero denominator renders as `—`, never `0%`. Rounded half up |
| Current streak | Decided sessions (attended or skipped) walked newest-first, counting attended until the first skip. Cancelled and unconfirmed sessions are stepped over without breaking it |
| By dance category | Segment minutes grouped by `DanceCategory` across attended sessions, with a session counted once per category it touches |
| By event type | Attended sessions grouped by `TrainingEventType`: wall-clock minutes and session count |

Two consequences worth stating explicitly:

**Hours count attendance, not intention.** A planned session that never happened
contributes nothing.

**The streak ignores the period selector.** It is always computed over the user's full
history and labelled "all time" on the card. A streak truncated at a window boundary
would report a number that is not the user's streak.

**The category breakdown reconciles with the hours card.** Sessions carry optional
`TrainingEventSegment`s, whose minutes may sum to less than the session's wall clock —
the remainder being warm-ups and breaks — and events such as competitions carry no
segments at all. All of that unattributed time collects in a trailing "Unassigned" slice,
so the chart totals the same hours the KPI card claims. The slice will be conspicuous
until segments are filled in regularly, which is honest rather than a defect.

## Components

### `dto/TrainingStats.kt`

```kotlin
enum class StatsPeriod { LAST_30_DAYS, THIS_YEAR, ALL_TIME }

data class BreakdownSlice(
    val label: String,
    val minutes: Long,
    val sessionCount: Int,
    val color: String
)

data class SessionCounts(
    val upcoming: Int,
    val unconfirmed: Int,
    val attended: Int,
    val skipped: Int,
    val cancelled: Int
)

data class TrainingStats(
    val period: StatsPeriod,
    val totalMinutesTrained: Long,
    val counts: SessionCounts,
    val attendanceRatePercent: Int?,
    val currentStreak: Int,
    val byCategory: List<BreakdownSlice>,
    val byEventType: List<BreakdownSlice>
)
```

`attendanceRatePercent` is nullable because "no decided sessions yet" is a different
state from "nothing attended", and the template must render them differently.

### `service/TrainingStatsService` + `TrainingStatsServiceImpl`

Interface and implementation pair, per repo convention. A single method:

```kotlin
fun statsForCurrentUser(period: StatsPeriod): TrainingStats
```

Read-only, so it publishes no `DomainEvent` — the activity feed records changes, and
looking at a dashboard is not one.

The streak walk lives in its own private function operating on a list of events, so
phase 4 can lift it into a shared position without untangling it from the rest.

### `repository/TrainingEventRepository`

One new method, leaving the existing lazy one untouched:

```kotlin
@EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
fun findAllByCreatedBy(createdBy: AppUser): List<TrainingEvent>
```

The entity graph exists so the category breakdown does not issue a query per session.

### `controller/web/TrainingStatsWebController.kt`

A separate controller at `/training-events/stats` rather than a fifth responsibility on
the 352-line `TrainingEventWebController`. One `@GetMapping` taking
`@RequestParam(defaultValue = "ALL_TIME") period: StatsPeriod`; Spring binds the enum and
rejects anything else with a 400.

`NavbarAdvice.activeNav()` already matches on the `/training-events` prefix, so the
navbar highlights correctly with no change there.

### `TrainingEventPalette`

Extended with a small ordered chart palette drawn from the Noble Harmony tokens.
`DanceCategory` has no colour column and is not gaining one for a chart; slices take
their colour from the category's sorted position, so a style keeps the same colour
between renders and across both charts. The palette object is already the single place
a training concept becomes a colour, which is why the chart colours belong there and not
in JavaScript.

### `templates/training-events/stats.html`

Mobile-first, in this order:

1. Period selector — three links, the active one styled as such.
2. KPI grid — two columns on mobile, four from `md`: hours trained, attendance rate,
   current streak (labelled "all time"), sessions attended.
3. Unconfirmed nudge — rendered only when `counts.unconfirmed` is non-zero, linking to the
   training list filtered to `PLANNED`. It follows the selected period like everything
   else on the page except the streak; since the page defaults to all time, nothing is
   hidden unless the user deliberately narrows the window.
4. Two `<canvas>` elements, each carrying its slices as JSON in a `data-slices`
   attribute, with a value list rendered beneath it in Thymeleaf.
5. Empty state when the user has no events at all: no charts, a prompt to add a session.

The value list under each chart is simultaneously the legend, the screen-reader-accessible
form of the chart, and the fallback when JavaScript does not run. Keeping it in the
template also keeps its classes inside Tailwind's scan path — `tailwind.config.js` has
`content: ['../templates/**/*.html']`, so classes assembled in `static/js/` are never
emitted.

`list.html` and `calendar.html` each gain a link to the stats page beside the existing
header button, so the three views reach one another.

### `static/js/training-stats.js`

Reads the two `data-slices` attributes, draws a doughnut for categories and a horizontal
bar for event types, and formats tooltip durations as `3h 20m`. Colours come from the
payload; the file contains no palette of its own.

Chart.js loads from `https://unpkg.com/chart.js@4.5.0/dist/chart.umd.js`, the same way
`calendar.html` loads FullCalendar. `SecurityConfig` already allows
`script-src 'self' https://unpkg.com`, so **no CSP change is required** — a jsdelivr or
cdnjs URL would be blocked silently instead.

## Testing

`TrainingStatsServiceTest` — JUnit 5 and Mockito against `TrainingStatsServiceImpl` with
a mocked repository and `AppUserService`, matching the existing service tests. Written
before the implementation. Cases:

- Hours count attended sessions only; planned, skipped and cancelled contribute nothing.
- Attendance rate excludes cancelled and unconfirmed sessions from both sides.
- Attendance rate is null when no session has been decided.
- The streak breaks at the first skip.
- The streak steps over cancelled and unconfirmed sessions without breaking.
- The streak is unaffected by the selected period.
- The category breakdown emits an "Unassigned" slice for unattributed time, and none when
  segments fill every attended session exactly.
- A session touching two categories counts once in each category's session count.
- Period boundaries include and exclude the sessions on the edges as intended.

Verified by hand afterwards: the page renders with real data, both charts draw, the
period links change the numbers, the nudge appears and links correctly, and the empty
state shows for a user with no sessions.
