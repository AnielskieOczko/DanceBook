# Training Statistics Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only dashboard at `/training-events/stats` summarising the signed-in user's training history — hours trained, attendance rate, current streak, and breakdowns by dance style and event type.

**Architecture:** A `TrainingStatsService` loads the user's training events in one query and computes every statistic in Kotlin, returning an immutable `TrainingStats` DTO. A dedicated web controller renders a server-side Thymeleaf page from that DTO; two Chart.js canvases receive their data as JSON in `data-` attributes. No schema change, no new entity, no JSON API.

**Tech Stack:** Kotlin 1.9 / Java 21, Spring Boot 3.5 (Web MVC, Data JPA), Thymeleaf, Tailwind 3, Chart.js 4 from unpkg, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-07-training-stats-dashboard-design.md`

## Global Constraints

- **Package root:** `com.jankowski.rafal.dancebook`. Source under `src/main/kotlin/com/jankowski/rafal/dancebook/`, tests under `src/test/kotlin/com/jankowski/rafal/dancebook/`.
- **Service convention:** every service is an `interface` plus a `<Name>ServiceImpl` class annotated `@Service`.
- **No Flyway migration in this plan.** `spring.jpa.hibernate.ddl-auto=validate` — but this feature adds no entity and no column. If you find yourself writing a migration, you have left the plan.
- **No domain event.** `ActivityEventListener` records changes; reading a dashboard is not a change.
- **CSP:** `SecurityConfig` allows `script-src 'self' https://unpkg.com` only. Chart.js must come from `unpkg.com`, and page JavaScript must live in an external file under `static/js/` — inline `<script>` bodies are blocked.
- **Tailwind scan path:** `frontend/tailwind.config.js` has `content: ['../templates/**/*.html']`. Every utility class must appear literally in a template; classes assembled in JavaScript are never emitted into `output.css`.
- **Colours:** Noble Harmony tokens only. Hex values for JavaScript are resolved in `TrainingEventPalette`, never in `.js` files.
- **Test command:** `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`.
- **Full build:** `./gradlew build` (compiles, runs tests, builds Tailwind CSS).
- **Commits:** end every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## File Structure

| File | Responsibility |
| --- | --- |
| `dto/TrainingStats.kt` (create) | `StatsPeriod`, `SessionCounts`, `BreakdownSlice`, `TrainingStats`, and the `formatMinutes` helper. Pure data plus display labels. |
| `repository/TrainingEventRepository.kt` (modify) | One new `@EntityGraph` finder that loads segments and their categories eagerly. |
| `service/TrainingStatsService.kt` (create) | Interface: one method. |
| `service/TrainingStatsServiceImpl.kt` (create) | All statistic computation. Pure function of the loaded event list. |
| `controller/TrainingEventPalette.kt` (modify) | Chart slice colours, so no hex lives in JavaScript. |
| `controller/web/TrainingStatsWebController.kt` (create) | One GET, binds the period, serialises chart JSON. |
| `templates/training-events/stats.html` (create) | KPI cards, period links, nudge, chart canvases, value lists, empty state. |
| `templates/training-events/list.html` (modify) | Link to the stats page. |
| `templates/training-events/calendar.html` (modify) | Link to the stats page. |
| `static/js/training-stats.js` (create) | Reads `data-slices`, draws two Chart.js charts. No data, no colours of its own. |
| `test/.../service/TrainingStatsServiceTest.kt` (create) | Every statistic definition, one test each. |

---

### Task 1: Stats DTOs, the query, and the basic figures

Hours trained, the five session counts, attendance rate, and period filtering. The streak and the breakdowns arrive in tasks 2 and 3.

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingStats.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsService.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingEvent` (`startTime`, `endTime`, `attendanceStatus`, `eventType`, `segments`, `durationMinutes`, `isAwaitingConfirmation`), `AttendanceStatus`, `AppUserService.getCurrentUser()`.
- Produces: `StatsPeriod`, `SessionCounts`, `BreakdownSlice`, `TrainingStats`, `formatMinutes(Long): String`, `TrainingStatsService.statsForCurrentUser(StatsPeriod): TrainingStats`, and `TrainingEventRepository.findAllByCreatedBy(AppUser): List<TrainingEvent>`.

**Background you need:** `isAwaitingConfirmation` is a computed property on `TrainingEvent` — true when the status is still `PLANNED` and `endTime` is in the past. It means "we do not know whether this happened", which is why it is its own bucket rather than being folded into planned or skipped. Nothing in this feature injects a `Clock`; the entity itself calls `LocalDateTime.now()`, so tests build events relative to `LocalDateTime.now()` the same way `TrainingEventServiceTest` does.

- [ ] **Step 1: Write the DTO file**

Create `dto/TrainingStats.kt`:

```kotlin
package com.jankowski.rafal.dancebook.dto

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A window over training history, chosen on the dashboard.
 *
 * Windows are left-bounded only: "last 30 days" means everything from 30 days ago
 * *onwards*, future sessions included, so the upcoming-sessions figure does not read zero
 * on every period but [ALL_TIME].
 */
enum class StatsPeriod(val label: String) {
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year"),
    ALL_TIME("All time");

    /** The earliest day this period admits, or null when it admits everything. */
    fun earliestDay(today: LocalDate): LocalDate? = when (this) {
        LAST_30_DAYS -> today.minusDays(30)
        THIS_YEAR -> today.withDayOfYear(1)
        ALL_TIME -> null
    }

    /** Membership is decided by the session's start, not its end. */
    fun contains(startTime: LocalDateTime, today: LocalDate = LocalDate.now()): Boolean {
        val earliest = earliestDay(today) ?: return true
        return !startTime.toLocalDate().isBefore(earliest)
    }
}

/**
 * Five disjoint buckets: every session in the period lands in exactly one.
 *
 * [unconfirmed] takes precedence over the raw status, matching how
 * `TrainingEventPalette.swatchFor` decides a session's colour — a past session still
 * marked planned is "needs confirming" everywhere in the app, not "planned".
 */
data class SessionCounts(
    val upcoming: Int,
    val unconfirmed: Int,
    val attended: Int,
    val skipped: Int,
    val cancelled: Int
) {
    val total: Int get() = upcoming + unconfirmed + attended + skipped + cancelled
}

/** One slice of a breakdown chart, carrying its own colour and pre-formatted duration. */
data class BreakdownSlice(
    val label: String,
    val minutes: Long,
    val sessionCount: Int,
    val color: String
) {
    /** Serialised alongside the rest, so chart tooltips need no formatter of their own. */
    val durationLabel: String get() = formatMinutes(minutes)
}

data class TrainingStats(
    val period: StatsPeriod,
    val totalMinutesTrained: Long,
    val counts: SessionCounts,
    /** Null when nothing has been decided yet — a different state from "nothing attended". */
    val attendanceRatePercent: Int?,
    /** Always over full history, never the selected period. A truncated streak is a lie. */
    val currentStreak: Int,
    val byCategory: List<BreakdownSlice>,
    val byEventType: List<BreakdownSlice>
) {
    val totalTrainedLabel: String get() = formatMinutes(totalMinutesTrained)
}

/** `195` becomes `3h 15m`, `120` becomes `2h`, `45` becomes `45m`. */
fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "${remainder}m"
        remainder == 0L -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}
```

- [ ] **Step 2: Add the repository finder**

In `repository/TrainingEventRepository.kt`, add these imports if missing:

```kotlin
import org.springframework.data.jpa.repository.EntityGraph
```

and add the method inside the interface:

```kotlin
    /**
     * Every session the user owns, with segments and their categories already loaded.
     *
     * The statistics page needs full history (the streak ignores the selected period) and
     * walks every segment, so the entity graph is what keeps this from issuing a query
     * per session.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByCreatedBy(createdBy: AppUser): List<TrainingEvent>
```

- [ ] **Step 3: Write the failing tests**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.formatMinutes
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.LocalDateTime
import java.util.UUID

class TrainingStatsServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingStatsService: TrainingStatsServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingStatsService = TrainingStatsServiceImpl(trainingEventRepository, appUserService)
    }

    /**
     * @param daysAgo negative values put the session in the future.
     */
    private fun event(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): TrainingEvent {
        val start = LocalDateTime.now().minusDays(daysAgo)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = currentUser
            this.segments = segments.mapIndexed { index, (category, segmentMinutes) ->
                TrainingEventSegment().apply {
                    trainingEvent = this@apply.let { _ -> null }
                    danceCategory = category
                    durationMinutes = segmentMinutes
                    sortOrder = index
                }
            }.toMutableList()
        }
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun given(vararg events: TrainingEvent) {
        `when`(trainingEventRepository.findAllByCreatedBy(currentUser)).thenReturn(events.toList())
    }

    @Test
    fun `hours count attended sessions only`() {
        given(
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 90),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED, minutes = 60),
            event(daysAgo = -2, status = AttendanceStatus.PLANNED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(90L, stats.totalMinutesTrained)
        assertEquals("1h 30m", stats.totalTrainedLabel)
    }

    @Test
    fun `every session lands in exactly one count bucket`() {
        given(
            event(daysAgo = -2, status = AttendanceStatus.PLANNED),
            event(daysAgo = 2, status = AttendanceStatus.PLANNED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        val counts = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).counts

        assertEquals(1, counts.upcoming)
        assertEquals(1, counts.unconfirmed)
        assertEquals(1, counts.attended)
        assertEquals(1, counts.skipped)
        assertEquals(1, counts.cancelled)
        assertEquals(5, counts.total)
    }

    @Test
    fun `attendance rate ignores cancelled and unconfirmed sessions`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED),
            event(daysAgo = 6, status = AttendanceStatus.PLANNED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(75, stats.attendanceRatePercent)
    }

    @Test
    fun `attendance rate is null when nothing has been decided`() {
        given(
            event(daysAgo = -1, status = AttendanceStatus.PLANNED),
            event(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        assertNull(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `attendance rate rounds half up`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 5, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 6, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 7, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 8, status = AttendanceStatus.SKIPPED)
        )

        // 5/8 = 62.5% exactly, which must round up rather than down.
        assertEquals(63, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `the period excludes sessions that start before its earliest day`() {
        given(
            event(daysAgo = 10, status = AttendanceStatus.ATTENDED, minutes = 60),
            event(daysAgo = 100, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(60L, stats.totalMinutesTrained)
        assertEquals(1, stats.counts.total)
    }

    @Test
    fun `a narrowed period still counts upcoming sessions`() {
        given(event(daysAgo = -5, status = AttendanceStatus.PLANNED))

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS).counts.upcoming)
    }

    @Test
    fun `durations format as hours and minutes`() {
        assertEquals("45m", formatMinutes(45))
        assertEquals("2h", formatMinutes(120))
        assertEquals("3h 15m", formatMinutes(195))
        assertEquals("0m", formatMinutes(0))
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: FAIL — compilation error, `TrainingStatsServiceImpl` is unresolved.

- [ ] **Step 5: Write the service interface**

Create `service/TrainingStatsService.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats

/** Read-only projection of the signed-in user's training history. */
interface TrainingStatsService {

    fun statsForCurrentUser(period: StatsPeriod): TrainingStats
}
```

- [ ] **Step 6: Write the implementation**

Create `service/TrainingStatsServiceImpl.kt`. The streak and breakdowns return placeholders until tasks 2 and 3 — the DTO shape is fixed now so later tasks only fill in bodies:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.roundToInt

/**
 * Every figure on the training dashboard.
 *
 * The whole of the user's history is loaded once and reduced in memory: the streak spans
 * all of it regardless of the selected period, the volumes are tiny, and a sequential walk
 * is far clearer here than the SQL that would express it.
 */
@Service
@Transactional(readOnly = true)
class TrainingStatsServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val appUserService: AppUserService
) : TrainingStatsService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingStatsServiceImpl::class.java)
    }

    override fun statsForCurrentUser(period: StatsPeriod): TrainingStats {
        val currentUser = appUserService.getCurrentUser()
        val allEvents = trainingEventRepository.findAllByCreatedBy(currentUser)
        log.debug("Computing {} training stats for user '{}'", period, currentUser.username)

        val inPeriod = allEvents.filter { period.contains(it.startTime) }
        val attended = inPeriod.filter { it.attendanceStatus == AttendanceStatus.ATTENDED }
        val counts = countsOf(inPeriod)

        return TrainingStats(
            period = period,
            totalMinutesTrained = attended.sumOf { it.durationMinutes },
            counts = counts,
            attendanceRatePercent = attendanceRate(counts),
            currentStreak = 0,
            byCategory = emptyList(),
            byEventType = emptyList()
        )
    }

    /**
     * Unconfirmed is tested first so a past session still marked planned reads as needing
     * confirmation rather than as upcoming — the same precedence `TrainingEventPalette`
     * applies when it picks a colour.
     */
    private fun countsOf(events: List<TrainingEvent>): SessionCounts {
        var upcoming = 0
        var unconfirmed = 0
        var attended = 0
        var skipped = 0
        var cancelled = 0
        for (event in events) {
            when {
                event.isAwaitingConfirmation -> unconfirmed++
                event.attendanceStatus == AttendanceStatus.ATTENDED -> attended++
                event.attendanceStatus == AttendanceStatus.SKIPPED -> skipped++
                event.attendanceStatus == AttendanceStatus.CANCELLED -> cancelled++
                else -> upcoming++
            }
        }
        return SessionCounts(upcoming, unconfirmed, attended, skipped, cancelled)
    }

    /** Null rather than zero when nothing has been decided: no data is not a bad record. */
    private fun attendanceRate(counts: SessionCounts): Int? {
        val decided = counts.attended + counts.skipped
        if (decided == 0) return null
        return (counts.attended * 100.0 / decided).roundToInt()
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: PASS — all nine tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingStats.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt
git commit -m "$(cat <<'MSG'
Count training hours, sessions and attendance rate

Loads the signed-in user's sessions once and reduces them in memory. Hours
count attended sessions only, the five session buckets are disjoint with
"needs confirming" taking precedence over the raw status, and the attendance
rate leaves out cancelled and unconfirmed sessions on both sides -- a
cancelled class is not a personal miss, and an unrecorded one is unknown
rather than skipped.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 2: Current streak

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingStatsServiceImpl` and the `event(...)`/`given(...)` test helpers from Task 1.
- Produces: a populated `TrainingStats.currentStreak`, computed by a private `currentStreak(List<TrainingEvent>): Int` that phase 4 (badges, issue #39) will reuse.

**Background you need:** the streak is the number of consecutive attended sessions counting back from the most recent, and it deliberately spans the user's whole history rather than the selected period. Cancelled sessions and sessions still awaiting confirmation are *stepped over* — they neither add to the streak nor end it — because neither is evidence that the user failed to show up. Only a `SKIPPED` session ends it.

- [ ] **Step 1: Write the failing tests**

Append these tests inside `TrainingStatsServiceTest`:

```kotlin
    @Test
    fun `the streak counts attended sessions back to the first skip`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 3, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `cancelled and unconfirmed sessions do not break the streak`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 2, status = AttendanceStatus.CANCELLED),
            event(daysAgo = 3, status = AttendanceStatus.PLANNED),
            event(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `an upcoming session does not break the streak`() {
        given(
            event(daysAgo = -3, status = AttendanceStatus.PLANNED),
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the streak spans full history even when the period is narrowed`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 100, status = AttendanceStatus.ATTENDED),
            event(daysAgo = 200, status = AttendanceStatus.ATTENDED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(1, stats.counts.total)
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `the streak is zero when the most recent decided session was skipped`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.SKIPPED),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(0, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: FAIL — the four streak expectations get `0`, since Task 1 hard-coded it.

- [ ] **Step 3: Implement the streak**

In `TrainingStatsServiceImpl`, replace `currentStreak = 0,` with:

```kotlin
            currentStreak = currentStreak(allEvents),
```

and add the private function below `countsOf`:

```kotlin
    /**
     * Consecutive attended sessions counting back from the most recent.
     *
     * Walks the user's whole history rather than the selected period: a streak cut off at
     * a window boundary would report a number that is not the user's streak. Cancelled
     * sessions and sessions still awaiting confirmation are stepped over, because neither
     * is evidence of a missed session; only a skip ends the run.
     *
     * Phase 4 (badges) reuses this walk, which is why it stands alone.
     */
    private fun currentStreak(allEvents: List<TrainingEvent>): Int {
        var streak = 0
        for (event in allEvents.sortedByDescending { it.startTime }) {
            if (event.isAwaitingConfirmation) continue
            when (event.attendanceStatus) {
                AttendanceStatus.ATTENDED -> streak++
                AttendanceStatus.SKIPPED -> return streak
                else -> continue
            }
        }
        return streak
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: PASS — all fourteen tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt
git commit -m "$(cat <<'MSG'
Count the current attendance streak

Consecutive attended sessions counting back from the most recent, over the
user's whole history rather than the selected period -- a streak truncated at
a window boundary would not be the user's streak. Cancelled and unconfirmed
sessions are stepped over rather than counted or treated as breaks, since
neither is evidence of a missed session.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 3: Breakdowns by dance category and event type

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/TrainingEventPalette.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt`

**Interfaces:**
- Consumes: `BreakdownSlice` and the test helpers from Task 1; `TrainingEventSegment` (`danceCategory`, `durationMinutes`).
- Produces: populated `TrainingStats.byCategory` / `byEventType`, and `TrainingEventPalette.chartColor(index: Int): String` plus `TrainingEventPalette.UNASSIGNED_COLOR`.

**Background you need:** a `TrainingEvent` optionally carries `TrainingEventSegment`s, each recording minutes spent on one `DanceCategory`. Segments may sum to *less* than the session's wall-clock length (warm-ups, breaks) and competitions or camps usually carry none at all. That unattributed time goes into a trailing "Unassigned" slice, so the chart totals the same hours the KPI card above it claims instead of quietly disagreeing. `DanceCategory` has no colour column and is not getting one; slices take their colour from their sorted position so a style keeps the same colour across renders and across both charts.

Event-type labels are the raw enum names (`TRAINING`, `CAMP`, …) because that is exactly how `list.html:121` already renders an event's type — the dashboard should not invent different wording for the same thing.

- [ ] **Step 1: Write the failing tests**

Append these tests inside `TrainingStatsServiceTest`:

```kotlin
    @Test
    fun `category minutes come from segments of attended sessions`() {
        val standard = category("Standard")
        val latin = category("Latin")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60, latin to 60)
            ),
            event(
                daysAgo = 2, status = AttendanceStatus.SKIPPED, minutes = 120,
                segments = listOf(standard to 120)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Latin", "Standard"), byCategory.map { it.label })
        assertEquals(60L, byCategory.first { it.label == "Standard" }.minutes)
        assertEquals(60L, byCategory.first { it.label == "Latin" }.minutes)
    }

    @Test
    fun `a session touching two categories counts once in each session count`() {
        val standard = category("Standard")
        val latin = category("Latin")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 30, standard to 30, latin to 60)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(1, byCategory.first { it.label == "Standard" }.sessionCount)
        assertEquals(60L, byCategory.first { it.label == "Standard" }.minutes)
        assertEquals(1, byCategory.first { it.label == "Latin" }.sessionCount)
    }

    @Test
    fun `time not covered by segments becomes an unassigned slice`() {
        val standard = category("Standard")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 90)
            ),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        val unassigned = byCategory.last()
        assertEquals("Unassigned", unassigned.label)
        assertEquals(90L, unassigned.minutes)
        assertEquals(180L, byCategory.sumOf { it.minutes })
    }

    @Test
    fun `no unassigned slice when segments fill every attended session`() {
        val standard = category("Standard")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            )
        )

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Standard"), byCategory.map { it.label })
    }

    @Test
    fun `event types are broken down by wall-clock minutes and session count`() {
        given(
            event(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60, type = TrainingEventType.TRAINING),
            event(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90, type = TrainingEventType.TRAINING),
            event(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            event(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val byType = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byEventType

        assertEquals(listOf("TRAINING", "CAMP"), byType.map { it.label })
        assertEquals(150L, byType.first().minutes)
        assertEquals(2, byType.first().sessionCount)
        assertEquals(240L, byType.last().minutes)
    }

    @Test
    fun `every slice carries a colour`() {
        val standard = category("Standard")
        given(
            event(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60)
            )
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        (stats.byCategory + stats.byEventType).forEach { slice ->
            assertTrue(slice.color.startsWith("#"), "slice ${slice.label} has no colour")
        }
    }
```

Add the import this needs at the top of the test file:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: FAIL — the breakdown lists are empty, so the label assertions fail first.

- [ ] **Step 3: Add chart colours to the palette**

In `controller/TrainingEventPalette.kt`, add inside the object, below the existing colour constants:

```kotlin
    /**
     * Slice colours for the statistics charts, cycled by the slice's sorted position so a
     * dance style keeps one colour across renders and across both charts.
     *
     * They live here for the same reason the status colours do: neither JavaScript nor a
     * script-applied class name can reach Tailwind's palette at runtime, so the tokens are
     * resolved server-side in one place.
     */
    private val CHART_COLORS = listOf(
        "#2e5d51", // success
        "#695d46", // secondary
        "#1e2524", // primary-container
        "#968881", // on-tertiary-container
        "#504530", // on-secondary-fixed-variant
        "#4f453f"  // on-tertiary-fixed-variant
    )

    /** Time inside an attended session that no segment claimed: present, but not a style. */
    const val UNASSIGNED_COLOR = "#c3c7c6" // outline-variant

    fun chartColor(index: Int): String = CHART_COLORS[index % CHART_COLORS.size]
```

- [ ] **Step 4: Implement the breakdowns**

In `TrainingStatsServiceImpl`, add the import:

```kotlin
import com.jankowski.rafal.dancebook.controller.TrainingEventPalette
```

Replace `byCategory = emptyList(),` and `byEventType = emptyList()` with:

```kotlin
            byCategory = categoryBreakdown(attended),
            byEventType = eventTypeBreakdown(attended)
```

and add both private functions:

```kotlin
    /**
     * Style time across attended sessions, with everything left over gathered into a
     * trailing "Unassigned" slice.
     *
     * Segments are optional and may cover less than a session's wall clock -- warm-ups and
     * breaks -- while competitions and camps usually carry none at all. Without the
     * remainder slice the chart would total fewer hours than the card above it claims.
     */
    private fun categoryBreakdown(attended: List<TrainingEvent>): List<BreakdownSlice> {
        val minutesByCategory = mutableMapOf<String, Long>()
        val sessionsByCategory = mutableMapOf<String, Int>()

        for (event in attended) {
            val categoriesTouched = mutableSetOf<String>()
            for (segment in event.segments) {
                val name = segment.danceCategory?.name ?: continue
                minutesByCategory.merge(name, segment.durationMinutes.toLong(), Long::plus)
                categoriesTouched += name
            }
            categoriesTouched.forEach { sessionsByCategory.merge(it, 1, Int::plus) }
        }

        val slices = minutesByCategory.keys.sorted().mapIndexed { index, name ->
            BreakdownSlice(
                label = name,
                minutes = minutesByCategory.getValue(name),
                sessionCount = sessionsByCategory[name] ?: 0,
                color = TrainingEventPalette.chartColor(index)
            )
        }

        val unassigned = attended.sumOf { it.durationMinutes } - minutesByCategory.values.sum()
        if (unassigned <= 0) return slices
        return slices + BreakdownSlice(
            label = UNASSIGNED_LABEL,
            minutes = unassigned,
            sessionCount = 0,
            color = TrainingEventPalette.UNASSIGNED_COLOR
        )
    }

    /**
     * Attended sessions grouped by kind, in the enum's own order so the chart does not
     * reshuffle as the data changes. Labels are the raw enum names, matching how the
     * training list already renders an event's type.
     */
    private fun eventTypeBreakdown(attended: List<TrainingEvent>): List<BreakdownSlice> =
        attended.groupBy { it.eventType }
            .toList()
            .sortedBy { (type, _) -> type.ordinal }
            .mapIndexed { index, (type, events) ->
                BreakdownSlice(
                    label = type.name,
                    minutes = events.sumOf { it.durationMinutes },
                    sessionCount = events.size,
                    color = TrainingEventPalette.chartColor(index)
                )
            }
```

Add the constant to the companion object:

```kotlin
        /** Shown as its own slice, so the chart reconciles with the hours card. */
        private const val UNASSIGNED_LABEL = "Unassigned"
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: PASS — all twenty tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/controller/TrainingEventPalette.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt
git commit -m "$(cat <<'MSG'
Break training time down by style and by kind of session

Style time comes from the optional segments of attended sessions, and
whatever they leave uncovered -- breaks, warm-ups, and competitions that
carry no segments at all -- collects in a trailing "Unassigned" slice so the
chart totals the same hours the card above it claims. Slice colours join the
status colours in TrainingEventPalette, since nothing downstream of Tailwind
can resolve a token at runtime.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 4: The page

Controller, template, and the links that reach it. Charts arrive in Task 5; this task ends with a working page whose numbers are all readable as text.

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingStatsWebController.kt`
- Create: `src/main/resources/templates/training-events/stats.html`
- Modify: `src/main/resources/templates/training-events/list.html:12-21`
- Modify: `src/main/resources/templates/training-events/calendar.html`

**Interfaces:**
- Consumes: `TrainingStatsService.statsForCurrentUser`, `StatsPeriod`, `TrainingStats`.
- Produces: the route `GET /training-events/stats?period=…`, and the model attributes `stats`, `periods`, `categoryChartJson`, `eventTypeChartJson`.

**Background you need:** `NavbarAdvice.activeNav()` matches on the `/training-events` prefix, so this route highlights the Training nav item with no change there — do not touch `NavbarAdvice`. Pages are rendered by replacing into `layout.html` via `th:replace="~{layout :: html(content=~{::section})}"`, and the section sets `activeNav` with `th:with`; copy that shape from `list.html`. The component classes `stat-card`, `stat-card-value`, `stat-card-label`, `card`, `chip`, `chip-active`, `btn-outline`, `btn-primary`, `page-header`, `page-title`, `page-subtitle`, `empty-state*` already exist in `frontend/input.css` — use them rather than inventing new utility soup.

Chart data is serialised to JSON in the controller with the injected Jackson `ObjectMapper` rather than assembled in the template, so the template holds markup only.

- [ ] **Step 1: Write the controller**

Create `controller/web/TrainingStatsWebController.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.service.TrainingStatsService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The training statistics dashboard.
 *
 * Its own controller rather than another responsibility on TrainingEventWebController,
 * which is already 350 lines of create, edit, calendar and series handling.
 *
 * NavbarAdvice.activeNav() matches on the /training-events prefix, so the navbar
 * highlights correctly without a branch of its own.
 */
@Controller
@RequestMapping("/training-events/stats")
class TrainingStatsWebController(
    private val trainingStatsService: TrainingStatsService,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun showStats(
        @RequestParam(defaultValue = "ALL_TIME") period: StatsPeriod,
        model: Model
    ): String {
        val stats = trainingStatsService.statsForCurrentUser(period)

        model.addAttribute("pageTitle", "Training stats")
        model.addAttribute("stats", stats)
        model.addAttribute("periods", StatsPeriod.entries.toTypedArray())
        // Serialised here so the template carries markup and nothing else.
        model.addAttribute("categoryChartJson", objectMapper.writeValueAsString(stats.byCategory))
        model.addAttribute("eventTypeChartJson", objectMapper.writeValueAsString(stats.byEventType))

        return "training-events/stats"
    }
}
```

- [ ] **Step 2: Write the template**

Create `templates/training-events/stats.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: html(content=~{::section})}">

<section th:with="activeNav='training-events'" class="space-y-lg">

    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div class="page-header mb-0">
            <h1 class="page-title text-3xl">Training stats</h1>
            <p class="page-subtitle" th:text="${stats.period.label}">All time</p>
        </div>
        <div class="flex items-center gap-2 shrink-0">
            <a href="/training-events" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">list</span>
                List
            </a>
            <a href="/training-events/calendar" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">calendar_month</span>
                Calendar
            </a>
        </div>
    </div>

    <!-- Period selector. Plain links, so the charts initialise once per page load
         instead of having to be destroyed and rebuilt on an HTMX swap. -->
    <div class="chip-group">
        <a th:each="option : ${periods}"
           th:href="@{/training-events/stats(period=${option})}"
           class="chip"
           th:classappend="${option == stats.period} ? 'chip-active' : ''"
           th:text="${option.label}">All time</a>
    </div>

    <!-- Empty state: no sessions to summarise. -->
    <div th:if="${stats.counts.total == 0}" class="card">
        <div class="empty-state">
            <span class="material-symbols-outlined empty-state-icon">monitoring</span>
            <h2 class="empty-state-title"
                th:text="${stats.period.name() == 'ALL_TIME'} ? 'No sessions logged yet' : 'No sessions in this period'">
                No sessions logged yet</h2>
            <p class="empty-state-text">Log a training session and this page will start
                keeping score: hours trained, how often you show up, and where your time goes.</p>
            <a href="/training-events/new" class="btn-primary">
                <span class="material-symbols-outlined text-[20px]">add</span>
                Add Session
            </a>
        </div>
    </div>

    <div th:if="${stats.counts.total > 0}" class="space-y-lg">

        <!-- KPI cards. Two up on a phone, four across from md. -->
        <div class="grid grid-cols-2 md:grid-cols-4 gap-gutter">
            <div class="stat-card">
                <p class="stat-card-label">Hours trained</p>
                <p class="stat-card-value" th:text="${stats.totalTrainedLabel}">12h 30m</p>
            </div>
            <div class="stat-card">
                <p class="stat-card-label">Attendance</p>
                <p class="stat-card-value"
                   th:text="${stats.attendanceRatePercent != null} ? ${stats.attendanceRatePercent} + '%' : '—'">75%</p>
            </div>
            <div class="stat-card">
                <p class="stat-card-label">Streak</p>
                <p class="stat-card-value" th:text="${stats.currentStreak}">4</p>
                <p class="text-sm text-text-secondary">consecutive, all time</p>
            </div>
            <div class="stat-card">
                <p class="stat-card-label">Attended</p>
                <p class="stat-card-value" th:text="${stats.counts.attended}">18</p>
                <p class="text-sm text-text-secondary"
                   th:text="${stats.counts.skipped} + ' skipped · ' + ${stats.counts.upcoming} + ' upcoming'">
                    2 skipped · 3 upcoming</p>
            </div>
        </div>

        <!-- Nudge. Unconfirmed sessions are excluded from every figure above, so the way
             to make these numbers truer is to go and confirm them. -->
        <a th:if="${stats.counts.unconfirmed > 0}"
           th:href="@{/training-events(attendanceStatuses='PLANNED')}"
           class="card p-4 flex items-center gap-4 hover:border-outline transition-colors">
            <span class="material-symbols-outlined text-secondary">help</span>
            <div>
                <p class="font-semibold text-text-primary"
                   th:text="${stats.counts.unconfirmed} + ' past sessions need confirming'">
                    3 past sessions need confirming</p>
                <p class="text-sm text-text-secondary">They count towards nothing above until
                    you mark them attended or skipped.</p>
            </div>
        </a>

        <!-- Charts. Each canvas carries its own slices; training-stats.js reads them and
             owns no data or colours of its own. The list beneath is the legend, the
             accessible form of the chart, and what you see when JavaScript does not run. -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-gutter">

            <div class="card p-6 space-y-4">
                <h2 class="text-lg font-heading font-medium text-text-primary">Time by style</h2>
                <div class="relative h-64">
                    <canvas id="category-chart" th:attr="data-slices=${categoryChartJson}"></canvas>
                </div>
                <ul class="space-y-2">
                    <li th:each="slice : ${stats.byCategory}" class="flex items-center gap-3 text-sm">
                        <span class="w-3 h-3 rounded-full shrink-0" th:style="'background-color:' + ${slice.color}"></span>
                        <span class="text-text-primary flex-1" th:text="${slice.label}">Standard</span>
                        <span class="text-text-secondary" th:text="${slice.durationLabel}">6h</span>
                    </li>
                </ul>
            </div>

            <div class="card p-6 space-y-4">
                <h2 class="text-lg font-heading font-medium text-text-primary">Time by session type</h2>
                <div class="relative h-64">
                    <canvas id="event-type-chart" th:attr="data-slices=${eventTypeChartJson}"></canvas>
                </div>
                <ul class="space-y-2">
                    <li th:each="slice : ${stats.byEventType}" class="flex items-center gap-3 text-sm">
                        <span class="w-3 h-3 rounded-full shrink-0" th:style="'background-color:' + ${slice.color}"></span>
                        <span class="text-text-primary flex-1" th:text="${slice.label}">TRAINING</span>
                        <span class="text-text-secondary"
                              th:text="${slice.durationLabel} + ' · ' + ${slice.sessionCount} + ' sessions'">6h · 4 sessions</span>
                    </li>
                </ul>
            </div>
        </div>
    </div>
</section>
</html>
```

- [ ] **Step 3: Link the page from the list and calendar views**

In `training-events/list.html`, inside the `div.flex.items-center.gap-2.shrink-0` (around line 12), add before the Calendar link:

```html
            <a href="/training-events/stats" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">monitoring</span>
                Stats
            </a>
```

In `training-events/calendar.html`, find the matching header button group and add the same anchor there.

- [ ] **Step 4: Build and check it compiles and renders**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — the Kotlin compiles, all twenty service tests pass, and `buildTailwind` regenerates `output.css` having now seen the new template.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingStatsWebController.kt \
        src/main/resources/templates/training-events/stats.html \
        src/main/resources/templates/training-events/list.html \
        src/main/resources/templates/training-events/calendar.html
git commit -m "$(cat <<'MSG'
Add the training statistics page

A third view beside the list and the calendar: KPI cards two-up on a phone
and four across on a desktop, a period selector built from plain links, and a
prompt to confirm past sessions -- which is the one action that makes every
other figure on the page truer. The charts are still empty canvases; the
value lists beneath them already carry the same numbers as text.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 5: Charts

**Files:**
- Create: `src/main/resources/static/js/training-stats.js`
- Modify: `src/main/resources/templates/training-events/stats.html`

**Interfaces:**
- Consumes: the `data-slices` attributes on `#category-chart` and `#event-type-chart`, each a JSON array of `{label, minutes, sessionCount, color, durationLabel}`.
- Produces: nothing other tasks depend on.

**Background you need:** `SecurityConfig` sets `script-src 'self' https://unpkg.com`, which means two things. Chart.js must be loaded from `unpkg.com` — a jsdelivr or cdnjs URL is blocked silently, with the page simply not drawing. And the page's own JavaScript must sit in an external file; an inline `<script>` body will not execute. `calendar.html:94` is the working precedent for both.

The slices already carry `durationLabel`, so tooltips need no formatting logic here.

- [ ] **Step 1: Write the chart script**

Create `static/js/training-stats.js`:

```javascript
/**
 * Draws the two charts on the training statistics page.
 *
 * Both datasets arrive as JSON on the canvas elements themselves, colours included, so
 * this file holds no data and no palette -- the colours are Noble Harmony tokens resolved
 * server-side in TrainingEventPalette, which is the only place a training concept becomes
 * a hex value.
 */
(function () {
    function readSlices(canvas) {
        try {
            return JSON.parse(canvas.dataset.slices || '[]');
        } catch (error) {
            console.error('[training-stats] could not read chart data', error);
            return [];
        }
    }

    function tooltipLabel(context) {
        var slice = context.chart.data.slices[context.dataIndex];
        return ' ' + slice.label + ': ' + slice.durationLabel;
    }

    function drawCategoryChart(canvas) {
        var slices = readSlices(canvas);
        if (slices.length === 0) return;

        new Chart(canvas, {
            type: 'doughnut',
            data: {
                slices: slices,
                labels: slices.map(function (slice) { return slice.label; }),
                datasets: [{
                    data: slices.map(function (slice) { return slice.minutes; }),
                    backgroundColor: slices.map(function (slice) { return slice.color; }),
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '62%',
                plugins: {
                    // The template renders the legend as a list, which screen readers can
                    // reach and which survives JavaScript being switched off.
                    legend: { display: false },
                    tooltip: { callbacks: { label: tooltipLabel } }
                }
            }
        });
    }

    function drawEventTypeChart(canvas) {
        var slices = readSlices(canvas);
        if (slices.length === 0) return;

        new Chart(canvas, {
            type: 'bar',
            data: {
                slices: slices,
                labels: slices.map(function (slice) { return slice.label; }),
                datasets: [{
                    data: slices.map(function (slice) { return slice.minutes; }),
                    backgroundColor: slices.map(function (slice) { return slice.color; }),
                    borderRadius: 6
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: {
                        ticks: {
                            // Minutes are the unit the data is in; hours are the unit a
                            // person reads an axis in.
                            callback: function (value) { return Math.round(value / 60) + 'h'; }
                        },
                        grid: { color: '#e5e2e1' }
                    },
                    y: { grid: { display: false } }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: tooltipLabel } }
                }
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var categoryCanvas = document.getElementById('category-chart');
        if (categoryCanvas) drawCategoryChart(categoryCanvas);

        var eventTypeCanvas = document.getElementById('event-type-chart');
        if (eventTypeCanvas) drawEventTypeChart(eventTypeCanvas);
    });
})();
```

- [ ] **Step 2: Load the scripts from the template**

At the end of `templates/training-events/stats.html`, immediately before the closing `</section>` tag, add:

```html
    <!-- Chart.js comes from unpkg because script-src allows that origin and nothing else
         external; a jsdelivr or cdnjs URL would be blocked with no visible error. -->
    <script src="https://unpkg.com/chart.js@4.5.0/dist/chart.umd.js"></script>
    <script th:src="@{/js/training-stats.js}"></script>
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/js/training-stats.js \
        src/main/resources/templates/training-events/stats.html
git commit -m "$(cat <<'MSG'
Draw the training breakdown charts

A doughnut for style and a horizontal bar for session type, both reading
their slices -- colours and formatted durations included -- from the canvas
data attributes, so the script owns no palette and no formatting of its own.
Chart.js loads from unpkg because the content security policy allows that
origin and no other.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
MSG
)"
```

---

### Task 6: Verify against a running application

The unit tests cover every statistic definition, but nothing so far proves the page renders, the charts draw, or the links land. This task is manual because running the app needs a Postgres container and real Google credentials.

**Files:** none — this task changes nothing unless it finds a defect.

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, twenty `TrainingStatsServiceTest` tests passing among the rest.

- [ ] **Step 2: Start the dependencies and the app**

```bash
docker compose up -d postgres
./gradlew bootRun
```

The app reads every setting from environment variables with no defaults and will not boot without `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN` and `GOOGLE_DRIVE_FOLDER_ID`.

- [ ] **Step 3: Walk the checklist**

Sign in, then confirm each of these at `http://localhost:8080/training-events/stats`:

1. The Training item in the navbar is highlighted.
2. The four KPI cards read sensibly against the sessions you have; hours count only sessions marked attended.
3. Both charts draw, and their colours match the dots in the list beneath each one.
4. The browser console is free of content-security-policy errors. If a chart is missing and the console reports a blocked script, the Chart.js URL is not the unpkg one.
5. Clicking each period chip changes the numbers; the active chip is styled differently; the streak stays the same across all three.
6. With at least one past session still marked planned, the confirmation prompt appears and its link opens the training list filtered to planned sessions.
7. At a phone width (~390px) the KPI cards sit two per row, the charts stack, and the page does not scroll sideways.
8. A user with no sessions sees the empty state and no charts.

- [ ] **Step 4: Close the issue**

If everything passes, the work is ready for a pull request against `main` referencing issue #37.
