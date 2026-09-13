# Calendar View Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a calendar a context you work in — one active calendar (or "All calendars") that scopes the agenda, calendar grid, statistics, timeline and history, and determines where new sessions go.

**Architecture:** An `ActiveCalendarService` keeps the selection in the `HttpSession`. A `@ControllerAdvice` restricted to the four training web controllers exposes it to templates, where a shared selector fragment sets it. Each view filters by an explicit calendar parameter passed from its controller — services never read session state. Statistics and history need the calendar denormalised onto `training_record`, because that table deliberately outlives the session it came from.

**Tech Stack:** Kotlin 1.9 / Java 21, Spring Boot 3.5 (Web MVC, Data JPA, Thymeleaf), PostgreSQL + Flyway, HTMX 2, Tailwind 3, JUnit 5 + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-13-calendar-as-view-context-design.md`

## Global Constraints

- **Schema change ⇒ Flyway migration.** `spring.jpa.hibernate.ddl-auto=validate`; an entity field with no column fails at startup. Highest migration is currently **V29**, so this plan's migration is **V30**.
- **Mutating service method ⇒ publish the matching `DomainEvent`.** This plan adds no mutating service methods, so it publishes none; do not invent any.
- **New list/filter endpoint ⇒ HTMX fragment shape** per `controller/web/DanceFigureWebController.kt`.
- **New top-level route ⇒ `activeNav()` branch** in `controller/web/NavbarAdvice.kt`. This plan adds no top-level route — every path stays under `/training-events`, which `activeNav()` already matches.
- **Colours come from the Noble Harmony tokens** in `src/main/resources/frontend/tailwind.config.js`. Tailwind scans templates only; never edit generated `static/css/output.css`.
- **CSP forbids inline `<script>` and `onclick`.** Behaviour lives in `src/main/resources/static/js/*.js`. `static/js/main.js` already adds the CSRF header on `htmx:configRequest`, so htmx requests need no CSRF work.
- **Never move an existing session between calendars.** `update` must keep resolving through `calendarOf(event)` and must never read `request.calendarId`.
- Run `./gradlew build` before every commit. Docker must be running for Testcontainers tests.

---

### Task 1: Denormalise the calendar onto `training_record`

`training_record` has no foreign key to `training_event` on purpose — it outlives the session. So it cannot be filtered by calendar via a join, and needs its own copy, exactly as it already copies `title`, `event_type` and `category_name`.

**Files:**
- Create: `src/main/resources/db/migration/V30__add_training_record_calendar.sql`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriter.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/migration/TrainingRecordCalendarMigrationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `TrainingRecord.calendarId: UUID?`, `TrainingRecord.calendarName: String?`, and `TrainingRecordRepository.findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(createdBy: AppUser, calendarId: UUID): List<TrainingRecord>`.

- [ ] **Step 1: Write the failing migration test**

Follow the shape of the existing `src/test/kotlin/com/jankowski/rafal/dancebook/migration/TrainingRecordBackfillTest.kt` — raw Flyway plus Testcontainers, no Spring context, so it needs none of the app's environment variables.

```kotlin
package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@Testcontainers
class TrainingRecordCalendarMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun migrateTo(version: String) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(version))
        .load()
        .migrate()

    @Test
    fun `V30 backfills the calendar for records whose session survives and leaves orphans null`() {
        migrateTo("29")

        val userId = UUID.randomUUID()
        val calendarId = UUID.randomUUID()
        val liveEventId = UUID.randomUUID()
        val liveRecordId = UUID.randomUUID()
        val orphanRecordId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES ('$userId', 'v30-tester', 'V30 Tester', 'x', 'USER', NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_calendar
                        (id, google_calendar_id, display_name, is_default, enabled, created_at, updated_at)
                    VALUES ('$calendarId', 'v30@group.calendar.google.com', 'Club Training', true, true, NOW(), NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_event
                        (id, title, start_time, end_time, event_type, attendance_status,
                         calendar_id, created_by_id, created_at, updated_at)
                    VALUES ('$liveEventId', 'Latin technique', TIMESTAMP '2026-03-02 18:00:00',
                            TIMESTAMP '2026-03-02 19:00:00', 'TRAINING', 'ATTENDED',
                            '$calendarId', '$userId', NOW(), NOW())
                    """.trimIndent()
                )
                // A record whose session still exists.
                st.executeUpdate(
                    """
                    INSERT INTO training_record
                        (id, training_event_id, occurred_at, duration_minutes, outcome, title,
                         event_type, created_by_id, created_at, updated_at)
                    VALUES ('$liveRecordId', '$liveEventId', TIMESTAMP '2026-03-02 18:00:00', 60,
                            'ATTENDED', 'Latin technique', 'TRAINING', '$userId', NOW(), NOW())
                    """.trimIndent()
                )
                // An already-orphaned record: its session id matches nothing.
                st.executeUpdate(
                    """
                    INSERT INTO training_record
                        (id, training_event_id, occurred_at, duration_minutes, outcome, title,
                         event_type, created_by_id, orphaned_at, created_at, updated_at)
                    VALUES ('$orphanRecordId', '${UUID.randomUUID()}', TIMESTAMP '2026-02-01 18:00:00', 90,
                            'ATTENDED', 'Deleted session', 'TRAINING', '$userId', NOW(), NOW(), NOW())
                    """.trimIndent()
                )
            }
        }

        migrateTo("30")

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeQuery(
                    "SELECT calendar_id, calendar_name FROM training_record WHERE id = '$liveRecordId'"
                ).use {
                    assertTrue(it.next())
                    assertEquals(calendarId.toString(), it.getString("calendar_id"))
                    assertEquals("Club Training", it.getString("calendar_name"))
                }
                st.executeQuery(
                    "SELECT calendar_id, calendar_name FROM training_record WHERE id = '$orphanRecordId'"
                ).use {
                    assertTrue(it.next())
                    assertNull(it.getObject("calendar_id"), "an orphaned record has no recoverable calendar")
                    assertNull(it.getObject("calendar_name"))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "*TrainingRecordCalendarMigrationTest*"`
Expected: FAIL — `ERROR: column "calendar_id" does not exist`.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V30__add_training_record_calendar.sql`:

```sql
-- Statistics and history scope to the active calendar, but training_record deliberately has
-- no foreign key to training_event (the record outlives the session), so it cannot reach a
-- calendar by joining. It keeps its own copy, exactly as it already copies title, event_type
-- and category_name.
ALTER TABLE training_record ADD COLUMN calendar_id   UUID;
ALTER TABLE training_record ADD COLUMN calendar_name VARCHAR(255);

-- Statistics and history filter on this.
CREATE INDEX idx_training_record_calendar ON training_record(calendar_id);

-- Backfill from each record's session, where that session still exists. Unlike V29's backfill
-- this needs no environment variable, so it belongs in the migration rather than a startup
-- component. Records already orphaned keep a null calendar: the calendar they came from is
-- genuinely unrecoverable, and they show under "All calendars" only.
UPDATE training_record r
SET calendar_id   = e.calendar_id,
    calendar_name = c.display_name
FROM training_event e
JOIN training_calendar c ON c.id = e.calendar_id
WHERE r.training_event_id = e.id;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "*TrainingRecordCalendarMigrationTest*"`
Expected: PASS.

- [ ] **Step 5: Add the entity fields**

In `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt`, beside the existing denormalised `title` and `eventType`:

```kotlin
    /**
     * The calendar the session lived in, copied at write time. Null for a record orphaned
     * before V30, whose calendar can no longer be recovered.
     */
    @Column(name = "calendar_id")
    var calendarId: UUID? = null

    /** Denormalised like [title], so history stays readable after the calendar is deleted. */
    @Column(name = "calendar_name")
    var calendarName: String? = null
```

- [ ] **Step 6: Write the failing writer test**

Add to `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriterTest.kt`:

```kotlin
    @Test
    fun `sync copies the session's calendar onto the record`() {
        val calendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "club@group.calendar.google.com"
            displayName = "Club Training"
        }
        val event = trainingEvent(AttendanceStatus.ATTENDED).apply { this.calendar = calendar }
        `when`(trainingRecordRepository.findByTrainingEventId(event.id!!)).thenReturn(null)

        writer.sync(event)

        val saved = ArgumentCaptor.forClass(TrainingRecord::class.java)
        verify(trainingRecordRepository).save(saved.capture())
        assertEquals(calendar.id, saved.value.calendarId)
        assertEquals("Club Training", saved.value.calendarName)
    }
```

Reuse whatever `trainingEvent(...)` helper the existing tests in that file use; if there is none, build the event inline the way the neighbouring tests do.

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingRecordWriterTest*"`
Expected: FAIL — `expected: <...> but was: <null>`.

- [ ] **Step 8: Set the fields in the writer**

In `TrainingRecordWriter.sync`, directly after `record.eventType = event.eventType`:

```kotlin
        record.calendarId = event.calendar?.id
        record.calendarName = event.calendar?.displayName
```

- [ ] **Step 9: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingRecordWriterTest*"`
Expected: PASS.

- [ ] **Step 10: Add the scoped repository query**

In `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt`, beside the existing `findAllByCreatedByOrderByOccurredAtDesc`:

```kotlin
    /** The same listing scoped to one calendar. Records with a null calendar are excluded. */
    fun findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(
        createdBy: AppUser,
        calendarId: UUID
    ): List<TrainingRecord>
```

- [ ] **Step 11: Full build and commit**

```bash
./gradlew build
git add src/main/resources/db/migration/V30__add_training_record_calendar.sql \
        src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriter.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/migration/TrainingRecordCalendarMigrationTest.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriterTest.kt
git commit -m "feat: record which calendar a training record came from"
```

---

### Task 2: `ActiveCalendarService`

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarService.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingCalendarService.findAll()`, `findById(id): TrainingCalendar?`, `findDefault(): TrainingCalendar?`, `requireDefault(): TrainingCalendar` (all exist).
- Produces: `ActiveCalendarService.active(): TrainingCalendar?`, `setActive(calendarId: UUID?)`, `selectable(): List<TrainingCalendar>`, `creationTarget(): TrainingCalendar`; and `TrainingEventRepository.calendarIdsInUse(): List<UUID>`.

- [ ] **Step 1: Add the repository query**

In `TrainingEventRepository`:

```kotlin
    /** Calendar ids that at least one session points at; drives which calendars stay selectable. */
    @Query("select distinct e.calendar.id from TrainingEvent e where e.calendar is not null")
    fun calendarIdsInUse(): List<UUID>
```

- [ ] **Step 2: Write the interface**

Create `ActiveCalendarService.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import java.util.UUID

/**
 * The calendar currently scoping the training views.
 *
 * A view preference rather than durable data, so it lives in the HTTP session and resets on
 * logout. Promoting it to a column on app_user later is a change behind this interface.
 */
interface ActiveCalendarService {

    /** The calendar scoping the training views, or null for "All calendars". */
    fun active(): TrainingCalendar?

    /** Sets the active calendar; null selects "All calendars". */
    fun setActive(calendarId: UUID?)

    /** Enabled calendars, plus any disabled calendar that still owns sessions. */
    fun selectable(): List<TrainingCalendar>

    /**
     * Where a new session goes: the active calendar, or the default under "All".
     * Throws [CalendarSyncException] when the active calendar is disabled.
     */
    fun creationTarget(): TrainingCalendar
}
```

- [ ] **Step 3: Write the failing tests**

Create `ActiveCalendarServiceTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class ActiveCalendarServiceTest {

    private val session = mock(HttpSession::class.java)
    private val trainingCalendarService = mock(TrainingCalendarService::class.java)
    private val trainingEventRepository = mock(TrainingEventRepository::class.java)

    private lateinit var service: ActiveCalendarService

    private fun calendar(name: String, isEnabled: Boolean = true) = TrainingCalendar().apply {
        id = UUID.randomUUID()
        googleCalendarId = "$name@group.calendar.google.com"
        displayName = name
        enabled = isEnabled
    }

    @BeforeEach
    fun setUp() {
        service = ActiveCalendarServiceImpl(session, trainingCalendarService, trainingEventRepository)
    }

    @Test
    fun `an unset session resolves to the default calendar`() {
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn(null)
        `when`(trainingCalendarService.findDefault()).thenReturn(default)

        assertEquals(default.id, service.active()?.id)
    }

    @Test
    fun `ALL resolves to no calendar`() {
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")

        assertNull(service.active())
    }

    @Test
    fun `setting All stores the ALL sentinel`() {
        service.setActive(null)

        verify(session).setAttribute("activeCalendarId", "ALL")
    }

    @Test
    fun `creationTarget falls back to the default under All`() {
        val default = calendar("Primary")
        `when`(session.getAttribute("activeCalendarId")).thenReturn("ALL")
        `when`(trainingCalendarService.requireDefault()).thenReturn(default)

        assertEquals(default.id, service.creationTarget().id)
    }

    @Test
    fun `creationTarget refuses a disabled active calendar rather than silently using the default`() {
        val disabled = calendar("Retired", isEnabled = false)
        `when`(session.getAttribute("activeCalendarId")).thenReturn(disabled.id.toString())
        `when`(trainingCalendarService.findById(disabled.id!!)).thenReturn(disabled)

        val error = assertThrows(CalendarSyncException::class.java) { service.creationTarget() }
        assertEquals(
            "Retired is disabled — choose another calendar to create a session.",
            error.message
        )
    }

    @Test
    fun `selectable includes enabled calendars and excludes a disabled one with no sessions`() {
        val enabled = calendar("Club")
        val disabledUnused = calendar("Typo", isEnabled = false)
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(enabled, disabledUnused))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(emptyList())

        assertEquals(listOf(enabled.id), service.selectable().map { it.id })
    }

    @Test
    fun `selectable keeps a disabled calendar that still owns sessions`() {
        val enabled = calendar("Club")
        val disabledUsed = calendar("Retired", isEnabled = false)
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(enabled, disabledUsed))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(listOf(disabledUsed.id!!))

        assertEquals(listOf(enabled.id, disabledUsed.id), service.selectable().map { it.id })
    }
}
```

- [ ] **Step 4: Run them to verify they fail**

Run: `./gradlew test --tests "*ActiveCalendarServiceTest*"`
Expected: FAIL — `ActiveCalendarServiceImpl` is unresolved.

- [ ] **Step 5: Write the implementation**

Create `ActiveCalendarServiceImpl.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ActiveCalendarServiceImpl(
    private val session: HttpSession,
    private val trainingCalendarService: TrainingCalendarService,
    private val trainingEventRepository: TrainingEventRepository
) : ActiveCalendarService {

    companion object {
        const val SESSION_KEY = "activeCalendarId"
        const val ALL = "ALL"
    }

    override fun active(): TrainingCalendar? {
        val stored = session.getAttribute(SESSION_KEY) as? String
            ?: return trainingCalendarService.findDefault()
        if (stored == ALL) return null
        // A calendar deleted while selected falls back rather than leaving a dead context.
        return trainingCalendarService.findById(UUID.fromString(stored))
            ?: trainingCalendarService.findDefault()
    }

    override fun setActive(calendarId: UUID?) {
        session.setAttribute(SESSION_KEY, calendarId?.toString() ?: ALL)
    }

    override fun selectable(): List<TrainingCalendar> {
        val inUse = trainingEventRepository.calendarIdsInUse().toSet()
        return trainingCalendarService.findAll().filter { it.enabled || it.id in inUse }
    }

    override fun creationTarget(): TrainingCalendar {
        val active = active() ?: return trainingCalendarService.requireDefault()
        if (!active.enabled) {
            // Deliberately not a silent fall back to the default: the session was asked for in
            // the calendar on screen, and filing it elsewhere without saying so is worse.
            throw CalendarSyncException(
                "${active.displayName} is disabled — choose another calendar to create a session."
            )
        }
        return active
    }
}
```

- [ ] **Step 6: Run them to verify they pass**

Run: `./gradlew test --tests "*ActiveCalendarServiceTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 7: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/ActiveCalendarServiceTest.kt
git commit -m "feat: hold the active training calendar in the session"
```

---

### Task 3: The selector — advice, fragment, endpoint

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingCalendarContextAdvice.kt`
- Create: `src/main/resources/templates/fragments/calendar-selector.html`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt`
- Modify: `src/main/resources/templates/training-events/list.html`, `calendar.html`, `stats.html`, `timeline.html`, `history.html`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/ActiveCalendarEndpointTest.kt`

**Interfaces:**
- Consumes: `ActiveCalendarService` from Task 2.
- Produces: model attributes `activeCalendar: TrainingCalendar?` and `selectableCalendars: List<TrainingCalendar>` on the four training web controllers; endpoint `POST /training-events/active-calendar`.

- [ ] **Step 1: Write the context advice**

Create `TrainingCalendarContextAdvice.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Supplies the active-calendar context to the training pages.
 *
 * Deliberately not part of NavbarAdvice, which feeds every page in the app: calendars are
 * irrelevant to the syllabus, materials and choreography pages, and loading them there would
 * be a wasted query on every request.
 */
@ControllerAdvice(
    assignableTypes = [
        TrainingEventWebController::class,
        TrainingStatsWebController::class,
        TrainingTimelineWebController::class,
        TrainingHistoryWebController::class
    ]
)
class TrainingCalendarContextAdvice(
    private val activeCalendarService: ActiveCalendarService
) {

    @ModelAttribute("activeCalendar")
    fun activeCalendar(): TrainingCalendar? = activeCalendarService.active()

    @ModelAttribute("selectableCalendars")
    fun selectableCalendars(): List<TrainingCalendar> = activeCalendarService.selectable()
}
```

- [ ] **Step 2: Write the selector fragment**

Create `src/main/resources/templates/fragments/calendar-selector.html`. Only classes already used elsewhere in the app (`form-label`, `form-select`) so Tailwind's template-only scan covers them.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>

<div th:fragment="calendarSelector"
     th:if="${selectableCalendars != null && selectableCalendars.size() > 1}"
     class="flex items-center gap-2">
    <label for="activeCalendar" class="form-label mb-0">Calendar</label>
    <select id="activeCalendar" name="calendarId" class="form-select"
            hx-post="/training-events/active-calendar" hx-trigger="change">
        <option value="ALL" th:selected="${activeCalendar == null}">All calendars</option>
        <option th:each="cal : ${selectableCalendars}"
                th:value="${cal.id}"
                th:selected="${activeCalendar != null && activeCalendar.id == cal.id}"
                th:text="${cal.enabled} ? ${cal.displayName} : ${cal.displayName} + ' (disabled)'">
            Calendar
        </option>
    </select>
</div>

</body>
</html>
```

- [ ] **Step 3: Write the failing endpoint test**

Create `ActiveCalendarEndpointTest.kt`, in the shape of the existing `DanceFigureWebControllerTest` (plain `ConcurrentModel`, mocked service, no Spring context):

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

class ActiveCalendarEndpointTest {

    private val activeCalendarService = mock(ActiveCalendarService::class.java)

    @Test
    fun `selecting a calendar stores it and asks htmx to refresh the page`() {
        val controller = ActiveCalendarController(activeCalendarService)
        val id = UUID.randomUUID()

        val response = controller.setActiveCalendar(id.toString())

        verify(activeCalendarService).setActive(id)
        assertEquals(204, response.statusCode.value())
        assertEquals("true", response.headers.getFirst("HX-Refresh"))
    }

    @Test
    fun `selecting All stores no calendar`() {
        val controller = ActiveCalendarController(activeCalendarService)

        controller.setActiveCalendar("ALL")

        verify(activeCalendarService).setActive(null)
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew test --tests "*ActiveCalendarEndpointTest*"`
Expected: FAIL — `ActiveCalendarController` is unresolved.

- [ ] **Step 5: Write the controller**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/ActiveCalendarController.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * Sets the calendar scoping the training views.
 *
 * Answers with HX-Refresh rather than a fragment: one shared selector is rendered on five
 * views that share no shape — the agenda's #events-list, the FullCalendar grid and three
 * others — so teaching it each one's target and fragment name would be far more machinery
 * than a page reload on an action taken a few times a day.
 */
@Controller
@RequestMapping("/training-events/active-calendar")
class ActiveCalendarController(
    private val activeCalendarService: ActiveCalendarService
) {

    @PostMapping
    fun setActiveCalendar(@RequestParam calendarId: String): ResponseEntity<Void> {
        activeCalendarService.setActive(
            if (calendarId == "ALL") null else UUID.fromString(calendarId)
        )
        return ResponseEntity.noContent().header("HX-Refresh", "true").build()
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew test --tests "*ActiveCalendarEndpointTest*"`
Expected: PASS.

- [ ] **Step 7: Render the selector on all five views**

In each of `training-events/list.html`, `calendar.html`, `stats.html`, `timeline.html` and `history.html`, add this inside the existing page header block, next to the page title:

```html
<div th:replace="~{fragments/calendar-selector :: calendarSelector}"></div>
```

Open each file and place it in the header row that already holds the page title and any action buttons; match the surrounding flex layout rather than introducing a new wrapper.

- [ ] **Step 8: Assert the selector renders only when it is useful**

Add to `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventViewRenderingTest.kt`, alongside its existing rendered-HTML assertions:

```kotlin
    @Test
    fun `the calendar selector is hidden with one calendar and shown with two`() {
        val club = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Club"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club))
        `when`(activeCalendarService.active()).thenReturn(club)

        var html = mockMvc.perform(get("/training-events"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertFalse(html.contains("id=\"activeCalendar\""))

        val home = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Home"; enabled = true }
        `when`(activeCalendarService.selectable()).thenReturn(listOf(club, home))

        html = mockMvc.perform(get("/training-events"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertTrue(html.contains("id=\"activeCalendar\""))
        assertTrue(html.contains("All calendars"))
    }
```

Add `@MockBean private lateinit var activeCalendarService: ActiveCalendarService` to the test class.

Run: `./gradlew test --tests "*TrainingEventViewRenderingTest*"`
Expected: PASS.

- [ ] **Step 9: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingCalendarContextAdvice.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/ActiveCalendarController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventViewRenderingTest.kt \
        src/main/resources/templates/fragments/calendar-selector.html \
        src/main/resources/templates/training-events/ \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/ActiveCalendarEndpointTest.kt
git commit -m "feat: add the calendar selector to the training views"
```

---

### Task 4: Scope the agenda list

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventSpecification.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventSpecificationTest.kt`

**Interfaces:**
- Consumes: `ActiveCalendarService.active()`.
- Produces: `TrainingEventSpecification.withFilters(..., calendarId: UUID? = null)`.

- [ ] **Step 1: Write the failing specification test**

Add to the existing `TrainingEventSpecificationTest.kt` (create it in the style of the repository tests if absent — it needs `@DataJpaTest` with the Testcontainers Postgres, matching how other repository-level tests in this project are wired):

```kotlin
    @Test
    fun `filters to one calendar and ignores a null calendar filter`() {
        val clubEvent = persistEvent(title = "Club session", calendar = clubCalendar)
        val homeEvent = persistEvent(title = "Home drill", calendar = homeCalendar)

        val scoped = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(createdBy = user, calendarId = clubCalendar.id)
        )
        assertEquals(listOf(clubEvent.id), scoped.map { it.id })

        val unscoped = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(createdBy = user, calendarId = null)
        )
        assertEquals(setOf(clubEvent.id, homeEvent.id), unscoped.map { it.id }.toSet())
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingEventSpecificationTest*"`
Expected: FAIL — no parameter named `calendarId`.

- [ ] **Step 3: Add the predicate**

In `TrainingEventSpecification.withFilters`, add the parameter after `awaitingConfirmation`:

```kotlin
        awaitingConfirmation: Boolean? = null,
        calendarId: UUID? = null
```

and the predicate, after the `awaitingConfirmation` block:

```kotlin
            if (calendarId != null) {
                // Null means "All calendars" and adds no predicate at all, so the common case
                // keeps its existing query plan.
                predicates.add(cb.equal(root.get<TrainingCalendar>("calendar").get<UUID>("id"), calendarId))
            }
```

Add `import com.jankowski.rafal.dancebook.model.TrainingCalendar` at the top.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingEventSpecificationTest*"`
Expected: PASS.

- [ ] **Step 5: Pass the active calendar from the controller**

In `TrainingEventWebController`, inject `private val activeCalendarService: ActiveCalendarService` into the constructor, then at the single place `TrainingEventSpecification.withFilters(...)` is built for the agenda listing, add the argument:

```kotlin
            calendarId = activeCalendarService.active()?.id
```

- [ ] **Step 6: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventSpecification.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventSpecificationTest.kt
git commit -m "feat: scope the training agenda to the active calendar"
```

---

### Task 5: Scope the calendar grid feed

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/api/TrainingCalendarApiController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventServiceTest.kt`

**Interfaces:**
- Consumes: `ActiveCalendarService.active()`.
- Produces: `TrainingEventService.findInRange(from: LocalDateTime, to: LocalDateTime, calendarId: UUID?): List<TrainingEvent>`.

- [ ] **Step 1: Add the scoped repository query**

In `TrainingEventRepository`, beside the existing range query:

```kotlin
    fun findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
        createdBy: AppUser,
        calendarId: UUID,
        to: LocalDateTime,
        from: LocalDateTime
    ): List<TrainingEvent>
```

- [ ] **Step 2: Write the failing service test**

Add to `TrainingEventServiceTest.kt`:

```kotlin
    @Test
    fun `findInRange scopes to a calendar when one is active`() {
        val calendarId = UUID.randomUUID()
        val from = LocalDateTime.now()
        val to = from.plusDays(7)
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(
            trainingEventRepository
                .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                    currentUser, calendarId, to, from
                )
        ).thenReturn(emptyList())

        service.findInRange(from, to, calendarId)

        verify(trainingEventRepository)
            .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                currentUser, calendarId, to, from
            )
    }
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingEventServiceTest*"`
Expected: FAIL — `findInRange` takes two arguments.

- [ ] **Step 4: Widen `findInRange`**

In `TrainingEventService`:

```kotlin
    fun findInRange(from: LocalDateTime, to: LocalDateTime, calendarId: UUID? = null): List<TrainingEvent>
```

In `TrainingEventServiceImpl`, replace the body:

```kotlin
    override fun findInRange(from: LocalDateTime, to: LocalDateTime, calendarId: UUID?): List<TrainingEvent> {
        val currentUser = appUserService.getCurrentUser()
        return if (calendarId == null) {
            trainingEventRepository.findAllByCreatedByAndStartTimeLessThanAndEndTimeGreaterThan(
                currentUser, to, from
            )
        } else {
            trainingEventRepository
                .findAllByCreatedByAndCalendarIdAndStartTimeLessThanAndEndTimeGreaterThan(
                    currentUser, calendarId, to, from
                )
        }
    }
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingEventServiceTest*"`
Expected: PASS.

- [ ] **Step 6: Scope the feed**

In `TrainingCalendarApiController`, inject `private val activeCalendarService: ActiveCalendarService` and pass the active calendar:

```kotlin
        trainingEventService.findInRange(
            parseFlexible(start), parseFlexible(end), activeCalendarService.active()?.id
        )
```

- [ ] **Step 7: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/api/TrainingCalendarApiController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventServiceTest.kt
git commit -m "feat: scope the calendar grid to the active calendar"
```

---

### Task 6: Scope the statistics dashboard

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingStatsWebController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingRecordRepository.findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc` (Task 1), `TrainingEventSpecification.withFilters(calendarId = ...)` (Task 4), `ActiveCalendarService.active()`.
- Produces: `TrainingStatsService.statsForCurrentUser(period: StatsPeriod, calendarId: UUID? = null): TrainingStats`.

- [ ] **Step 1: Write the failing test**

Add to `TrainingStatsServiceTest.kt`:

```kotlin
    @Test
    fun `stats scope to one calendar when one is active`() {
        val calendarId = UUID.randomUUID()
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(
            trainingRecordRepository
                .findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(currentUser, calendarId)
        ).thenReturn(emptyList())

        service.statsForCurrentUser(StatsPeriod.ALL_TIME, calendarId)

        verify(trainingRecordRepository)
            .findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(currentUser, calendarId)
        verify(trainingRecordRepository, never()).findAllByCreatedByOrderByOccurredAtDesc(currentUser)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingStatsServiceTest*"`
Expected: FAIL — `statsForCurrentUser` takes one argument.

- [ ] **Step 3: Widen the service**

In `TrainingStatsService`:

```kotlin
    fun statsForCurrentUser(period: StatsPeriod, calendarId: UUID? = null): TrainingStats
```

In `TrainingStatsServiceImpl.statsForCurrentUser`, replace the two loads at the top of the method:

```kotlin
        val allRecords = if (calendarId == null) {
            trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser)
        } else {
            trainingRecordRepository
                .findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(currentUser, calendarId)
        }
        val allEvents = trainingEventRepository.findAll(
            TrainingEventSpecification.withFilters(createdBy = currentUser, calendarId = calendarId)
        )
```

The existing reconciliation invariants this test class already asserts must continue to hold within a calendar scope — do not relax them.

- [ ] **Step 4: Run the whole stats suite**

Run: `./gradlew test --tests "*TrainingStatsServiceTest*"`
Expected: PASS, including the pre-existing reconciliation tests.

- [ ] **Step 5: Pass the active calendar from the controller**

In `TrainingStatsWebController`, inject `private val activeCalendarService: ActiveCalendarService` and change the call:

```kotlin
        val stats = trainingStatsService.statsForCurrentUser(period, activeCalendarService.active()?.id)
```

- [ ] **Step 6: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingStatsWebController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt
git commit -m "feat: scope training statistics to the active calendar"
```

---

### Task 7: Scope the history table

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryWebController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingRecordRepository.findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc` (Task 1).
- Produces: `TrainingHistoryService.historyForCurrentUser(calendarId: UUID? = null): TrainingHistory`.

- [ ] **Step 1: Write the failing test**

Add to `TrainingHistoryServiceTest.kt`:

```kotlin
    @Test
    fun `history scopes to one calendar when one is active`() {
        val calendarId = UUID.randomUUID()
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(
            trainingRecordRepository
                .findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(currentUser, calendarId)
        ).thenReturn(emptyList())

        service.historyForCurrentUser(calendarId)

        verify(trainingRecordRepository)
            .findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc(currentUser, calendarId)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingHistoryServiceTest*"`
Expected: FAIL — `historyForCurrentUser` takes no arguments.

- [ ] **Step 3: Widen the service**

In `TrainingHistoryService`:

```kotlin
    fun historyForCurrentUser(calendarId: UUID? = null): TrainingHistory
```

In `TrainingHistoryServiceImpl`, branch the record load exactly as Task 6 does for statistics: `findAllByCreatedByOrderByOccurredAtDesc` when `calendarId` is null, `findAllByCreatedByAndCalendarIdOrderByOccurredAtDesc` otherwise. Leave `deleteOrphanedRecord` untouched — a record is deleted by id and the calendar scope is irrelevant to it.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingHistoryServiceTest*"`
Expected: PASS.

- [ ] **Step 5: Pass the active calendar from the controller**

In `TrainingHistoryWebController`, inject `private val activeCalendarService: ActiveCalendarService` and change the call:

```kotlin
        model.addAttribute("history", trainingHistoryService.historyForCurrentUser(activeCalendarService.active()?.id))
```

- [ ] **Step 6: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryWebController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceTest.kt
git commit -m "feat: scope the training history to the active calendar"
```

---

### Task 8: Scope the timeline

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingTimelineWebController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineServiceTest.kt`

**Interfaces:**
- Consumes: `ActiveCalendarService.active()`.
- Produces: `TrainingTimelineService.timelineForCurrentUser(page: Int, calendarId: UUID? = null): TrainingTimeline`.

- [ ] **Step 1: Add the scoped paged query**

`TrainingTimelineServiceImpl` fetches in two steps — a paged probe, then `findAllByIdIn` for the window. Only the probe needs scoping; `findAllByIdIn` already works from ids. Add to `TrainingEventRepository`:

```kotlin
    fun findAllByCreatedByAndCalendarIdOrderByStartTimeDesc(
        createdBy: AppUser,
        calendarId: UUID,
        pageable: Pageable
    ): List<TrainingEvent>
```

- [ ] **Step 2: Write the failing test**

Add to `TrainingTimelineServiceTest.kt`:

```kotlin
    @Test
    fun `timeline scopes to one calendar when one is active`() {
        val calendarId = UUID.randomUUID()
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(
            trainingEventRepository.findAllByCreatedByAndCalendarIdOrderByStartTimeDesc(
                eq(currentUser), eq(calendarId), any()
            )
        ).thenReturn(emptyList())

        service.timelineForCurrentUser(0, calendarId)

        verify(trainingEventRepository)
            .findAllByCreatedByAndCalendarIdOrderByStartTimeDesc(eq(currentUser), eq(calendarId), any())
    }
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingTimelineServiceTest*"`
Expected: FAIL — `timelineForCurrentUser` takes one argument.

- [ ] **Step 4: Widen the service**

In `TrainingTimelineService`:

```kotlin
    fun timelineForCurrentUser(page: Int, calendarId: UUID? = null): TrainingTimeline
```

In `TrainingTimelineServiceImpl`, branch the probe query on `calendarId` being null, leaving the second-step `findAllByIdIn` call and all paging arithmetic unchanged.

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingTimelineServiceTest*"`
Expected: PASS.

- [ ] **Step 6: Pass the active calendar from the controller**

In `TrainingTimelineWebController`, inject `private val activeCalendarService: ActiveCalendarService` and change the call:

```kotlin
        val timeline = trainingTimelineService.timelineForCurrentUser(
            page.coerceAtLeast(0), activeCalendarService.active()?.id
        )
```

- [ ] **Step 7: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingTimelineWebController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingTimelineServiceTest.kt
git commit -m "feat: scope the training timeline to the active calendar"
```

---

### Task 9: Remove the per-session picker

The picker (#58) exists only because there was no view-level context. There now is, so it goes.

**Files:**
- Modify: `src/main/resources/templates/training-events/form.html`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventViewRenderingTest.kt`

**Interfaces:**
- Consumes: `ActiveCalendarService.creationTarget()` (Task 2).
- Produces: nothing new. `TrainingEventRequest.calendarId` stays on the DTO, populated server-side.

- [ ] **Step 1: Replace the picker rendering tests**

In `TrainingEventViewRenderingTest`, delete the three picker tests added by #58 — `should not render calendar picker on create form when only one calendar exists`, `should render calendar picker on create form when multiple calendars exist`, and `should render calendar picker disabled on edit form with multiple calendars` — and add one replacing them:

```kotlin
    @Test
    fun `the create form no longer offers a calendar picker`() {
        val html = mockMvc.perform(get("/training-events/new"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertFalse(html.contains("id=\"calendarId\""))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*TrainingEventViewRenderingTest*"`
Expected: FAIL — the form still contains `id="calendarId"`.

- [ ] **Step 3: Remove the control from the form**

In `training-events/form.html`, delete the whole `<div th:if="${calendars != null && calendars.size() > 1}">` block containing the `calendarId` select and its "Cannot be moved between calendars" hint, and restore the wrapper to its pre-#58 form:

```html
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-6 border-t border-border pt-6">
```

(removing the `th:classappend` that switched it between two and three columns).

- [ ] **Step 4: Populate the calendar server-side**

In `TrainingEventWebController`:

- delete the `calendars` and `defaultCalendarId` attributes from `populateFormOptions`
- in `showCreateForm`, drop the `defaultId` lookup and go back to `TrainingEventRequest()`
- in the create handler, before dispatching to `trainingSeriesService.create(request)` or `trainingEventService.create(request)`, set the target from the active calendar:

```kotlin
        val request = request.copy(calendarId = activeCalendarService.creationTarget().id)
```

`TrainingEventRequest` is a data class, so `copy` is available. Leave the update handler alone — it must keep ignoring `calendarId`.

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingEventViewRenderingTest*"`
Expected: PASS.

- [ ] **Step 6: Hide "New session" while a disabled calendar is active**

In `training-events/list.html` and `calendar.html`, guard the new-session control so it does not invite an action that will be refused:

```html
th:if="${activeCalendar == null || activeCalendar.enabled}"
```

- [ ] **Step 7: Full build and commit**

```bash
./gradlew build
git add src/main/resources/templates/training-events/ \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventViewRenderingTest.kt
git commit -m "feat: create sessions in the active calendar and drop the per-session picker"
```

---

## Manual verification

Automated tests cannot prove the session context behaves across real navigation. After Task 9:

```bash
docker compose up -d postgres
./gradlew bootRun
```

1. With two calendars configured, confirm the selector appears on the agenda, calendar, stats, timeline and history pages, and **not** when only one calendar is selectable.
2. Pick a calendar; confirm all five views scope to it and the choice survives moving between them.
3. Pick "All calendars"; confirm the figures match what the app showed before this work.
4. Create a session while a calendar is active; confirm it lands in that calendar.
5. Disable a calendar that owns sessions in `/admin`; confirm it still appears in the selector marked `(disabled)`, that its sessions remain visible, and that "New session" is hidden while it is active.
6. Add a calendar with a bad id so verification fails; confirm it does **not** appear in the selector.

**Expect the headline numbers to drop** when a calendar is selected — today's totals span every calendar, and only "All calendars" reproduces them. That is the intended behaviour, not a regression.
