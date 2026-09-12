# Training Records Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record confirmed training in its own permanent table so that deleting a calendar session no longer rewrites the user's statistics.

**Architecture:** A new `training_record` table (plus `training_record_segment`) holds one row per confirmed session, written in the same transaction as the session itself by a `TrainingRecordWriter` called from the existing transactional persistence beans. The record holds its session by plain id with no foreign key — the `activity_event` convention — and snapshots the title, duration and style names, so it survives the session's deletion as an *orphaned* record. `TrainingStatsServiceImpl` becomes deliberately hybrid: history figures (hours, attended/skipped, attendance rate, streak, both breakdowns) come from records; schedule figures (upcoming, unconfirmed, cancelled) stay on `training_event`. A new `/training-events/history` page lists records by month and lets an orphaned one be removed.

**Tech Stack:** Kotlin 1.9 / Java 21 · Spring Boot 3.5 (Web MVC, Data JPA, Thymeleaf) · PostgreSQL + Flyway · JUnit 5 + Mockito · Tailwind 3 + HTMX 2.

**Spec:** `docs/superpowers/plans/2026-09-12-training-records-spec.md`

## Global Constraints

- **Flyway migration required.** `spring.jpa.hibernate.ddl-auto=validate`, so every entity column needs a matching column. The next free number is **V28**; the file is `src/main/resources/db/migration/V28__add_training_record.sql`.
- **The record's session reference has no foreign key.** `training_event_id UUID NOT NULL UNIQUE` with no `REFERENCES` clause, following `activity_event.target_id`. A foreign key would either cascade the record away or block the session delete.
- **The record's owner reference restricts.** `created_by_id UUID NOT NULL REFERENCES app_user(id)` with *no* `ON DELETE` clause, following `activity_event.actor_id`. Never `ON DELETE CASCADE`.
- **Snapshot columns are immutable at the column level** where they can be, following `storage_cleanup_log`: `updatable = false` on `trainingEventId`, `createdBy` and `createdAt`.
- **Record writes commit with the session write.** They go inside the `@Transactional` methods of `TrainingEventPersistence` / `TrainingSeriesPersistence`. Never in a `@TransactionalEventListener`.
- **Colors come from `TrainingEventPalette`**, never new hex literals in templates or JS.
- **Tailwind only scans `src/main/resources/templates/**/*.html`.** Any new class must appear in a template; reuse the existing `tc-*`, `card`, `badge-*`, `btn-*` classes from `src/main/resources/frontend/input.css` rather than inventing new ones.
- **Test command:** `./gradlew test --tests "<pattern>"` from the repo root.
- Run `./gradlew build` before the final commit of each task that touches Kotlin.

---

## File Structure

**Created:**

| File | Responsibility |
| --- | --- |
| `src/main/resources/db/migration/V28__add_training_record.sql` | The two tables, their indexes, and the backfill of already-confirmed sessions |
| `model/TrainingOutcome.kt` | The two confirmed outcomes, and the mapping from `AttendanceStatus` |
| `model/TrainingRecord.kt` | The permanent record entity |
| `model/TrainingRecordSegment.kt` | The snapshotted style breakdown of one record |
| `repository/TrainingRecordRepository.kt` | Lookups by session id, and a user's full history |
| `service/TrainingRecordWriter.kt` | Keeps a record in step with its session; marks records orphaned |
| `service/TrainingHistoryService.kt` / `TrainingHistoryServiceImpl.kt` | The history read model, and orphaned-record removal |
| `dto/TrainingHistory.kt` | Month-grouped history DTOs |
| `controller/web/TrainingHistoryWebController.kt` | `/training-events/history` |
| `src/main/resources/templates/training-events/history.html` | The history page |
| `src/test/.../service/TrainingRecordWriterTest.kt` | Writer behaviour |
| `src/test/.../service/TrainingEventPersistenceTest.kt` | Record writes happen alongside session writes |
| `src/test/.../service/TrainingSeriesPersistenceTest.kt` | Bulk series changes orphan their records |
| `src/test/.../service/TrainingHistoryServiceTest.kt` | Grouping, orphan marking, removal rules |
| `src/test/.../controller/web/TrainingHistoryViewRenderingTest.kt` | The page renders against a real template |

**Modified:**

| File | Change |
| --- | --- |
| `dto/TrainingEventPalette.kt` | A `swatchFor(TrainingOutcome)` overload for records with no session |
| `repository/TrainingEventRepository.kt` | Drop `findAllByCreatedBy` — stats no longer needs the segment entity graph |
| `service/TrainingEventPersistence.kt` | Sync the record on insert/update; orphan it on delete |
| `service/TrainingSeriesPersistence.kt` | Orphan the records of occurrences removed in bulk |
| `service/TrainingStatsServiceImpl.kt` | Read history figures from records; keep schedule figures on events |
| `src/test/.../service/TrainingStatsServiceTest.kt` | Same 18 behaviours, now driven through records |
| `templates/training-events/list.html`, `stats.html`, `timeline.html` | A History link in the page-header button cluster |

---

## Task 1: The record schema and entities

**Files:**
- Create: `src/main/resources/db/migration/V28__add_training_record.sql`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingOutcome.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordSegment.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class TrainingOutcome { ATTENDED, SKIPPED }` with `TrainingOutcome.from(status: AttendanceStatus): TrainingOutcome?`
  - `class TrainingRecord` with `var id: UUID?`, `var trainingEventId: UUID?`, `var occurredAt: LocalDateTime`, `var durationMinutes: Int`, `var outcome: TrainingOutcome`, `var title: String`, `var eventType: TrainingEventType`, `var segments: MutableList<TrainingRecordSegment>`, `var createdBy: AppUser?`, `var orphanedAt: LocalDateTime?`, `var createdAt: LocalDateTime`, `var updatedAt: LocalDateTime`, and `val isOrphaned: Boolean`
  - `class TrainingRecordSegment` with `var id: UUID?`, `var trainingRecord: TrainingRecord?`, `var danceCategory: DanceCategory?`, `var categoryName: String`, `var durationMinutes: Int`, `var sortOrder: Int`, and `val label: String`
  - `interface TrainingRecordRepository : JpaRepository<TrainingRecord, UUID>` with `findByTrainingEventId(trainingEventId: UUID): TrainingRecord?`, `findAllByTrainingEventIdIn(trainingEventIds: Collection<UUID>): List<TrainingRecord>`, `findAllByCreatedByOrderByOccurredAtDesc(createdBy: AppUser): List<TrainingRecord>`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class TrainingRecordTest {

    @Test
    fun `only attended and skipped map to a confirmed outcome`() {
        assertEquals(TrainingOutcome.ATTENDED, TrainingOutcome.from(AttendanceStatus.ATTENDED))
        assertEquals(TrainingOutcome.SKIPPED, TrainingOutcome.from(AttendanceStatus.SKIPPED))
        assertNull(TrainingOutcome.from(AttendanceStatus.PLANNED))
        assertNull(TrainingOutcome.from(AttendanceStatus.CANCELLED))
    }

    @Test
    fun `a record is orphaned once it has been stamped`() {
        val record = TrainingRecord()

        assertFalse(record.isOrphaned)

        record.orphanedAt = LocalDateTime.now()
        assertTrue(record.isOrphaned)
    }

    @Test
    fun `a segment reads by its live category, and by its snapshot once that is gone`() {
        val category = DanceCategory().apply {
            id = UUID.randomUUID()
            name = "Standard"
        }
        val segment = TrainingRecordSegment().apply {
            danceCategory = category
            categoryName = "Standard"
            durationMinutes = 60
        }

        // A renamed category relabels the history that still points at it: same style, new name.
        category.name = "Ballroom"
        assertEquals("Ballroom", segment.label)

        // A deleted category leaves the link null, and the snapshot is what is left to read.
        segment.danceCategory = null
        assertEquals("Standard", segment.label)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.model.TrainingRecordTest"`
Expected: FAIL — compilation error, `TrainingOutcome` / `TrainingRecord` / `TrainingRecordSegment` unresolved.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V28__add_training_record.sql`:

```sql
-- Training history, kept apart from the calendar schedule.
--
-- training_event is the *schedule*: mirrored to Google Calendar, freely edited, freely
-- deleted. Statistics used to read it directly, so tidying the calendar rewrote the past --
-- delete a skipped session and your attendance rate improved. A confirmed session is now
-- also recorded here, and this table is what the statistics read.
--
-- Two conventions come straight from activity_event, the other table that outlives its
-- subject: the session is held as a plain id with no foreign key, so deleting a session
-- cannot take the record with it, and the owner reference restricts rather than cascades,
-- so deleting a user cannot silently destroy their history.
CREATE TABLE training_record (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_event_id UUID         NOT NULL UNIQUE,   -- no FK on purpose: the record outlives the session
    occurred_at       TIMESTAMP    NOT NULL,
    duration_minutes  INTEGER      NOT NULL,
    outcome           VARCHAR(20)  NOT NULL,          -- ATTENDED, SKIPPED
    title             VARCHAR(255) NOT NULL,
    event_type        VARCHAR(20)  NOT NULL,          -- TRAINING, CAMP, COMPETITION, WORKSHOP, OTHER
    created_by_id     UUID         NOT NULL REFERENCES app_user(id),
    orphaned_at       TIMESTAMP,                      -- stamped when the session is deleted
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Both the statistics page and the history page read one user's whole history, newest first.
CREATE INDEX idx_training_record_created_by_occurred ON training_record(created_by_id, occurred_at DESC);

-- The style breakdown, snapshotted alongside the record.
--
-- dance_category_id keeps the live link so renaming a style relabels the history that belongs
-- to it, rather than splitting one style into two. ON DELETE SET NULL means losing the
-- category costs the link and not the row; category_name is what the record then reads by.
CREATE TABLE training_record_segment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    training_record_id UUID         NOT NULL REFERENCES training_record(id) ON DELETE CASCADE,
    dance_category_id  UUID                  REFERENCES dance_category(id) ON DELETE SET NULL,
    category_name      VARCHAR(255) NOT NULL,
    duration_minutes   INTEGER      NOT NULL,
    sort_order         INTEGER      NOT NULL,
    CONSTRAINT unique_training_record_segment_sort_order UNIQUE (training_record_id, sort_order)
);

CREATE INDEX idx_training_record_segment_record ON training_record_segment(training_record_id);

-- Backfill: every session already marked attended or skipped becomes a record, with its
-- style breakdown. Sessions deleted before this migration are gone and cannot be recovered.
INSERT INTO training_record (
    id, training_event_id, occurred_at, duration_minutes, outcome, title, event_type,
    created_by_id, orphaned_at, created_at, updated_at
)
SELECT gen_random_uuid(),
       e.id,
       e.start_time,
       GREATEST(CAST(EXTRACT(EPOCH FROM (e.end_time - e.start_time)) / 60 AS INTEGER), 0),
       e.attendance_status,
       e.title,
       e.event_type,
       e.created_by_id,
       NULL,
       NOW(),
       NOW()
FROM training_event e
WHERE e.attendance_status IN ('ATTENDED', 'SKIPPED');

INSERT INTO training_record_segment (
    id, training_record_id, dance_category_id, category_name, duration_minutes, sort_order
)
SELECT gen_random_uuid(),
       r.id,
       s.dance_category_id,
       c.name,
       s.duration_minutes,
       s.sort_order
FROM training_record r
JOIN training_event_segment s ON s.training_event_id = r.training_event_id
JOIN dance_category c ON c.id = s.dance_category_id;
```

- [ ] **Step 4: Write the entities**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingOutcome.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

/**
 * The two outcomes a confirmed training session can have.
 *
 * Deliberately narrower than [AttendanceStatus]: PLANNED and CANCELLED are states of the
 * *schedule*, and a record only ever exists for a session whose outcome is known. Reusing
 * the wider enum would make "a record of a planned session" representable, and it is not.
 */
enum class TrainingOutcome {
    ATTENDED,
    SKIPPED;

    companion object {
        /** Null for the statuses that are not a confirmed outcome — unconfirmed is unknown. */
        fun from(status: AttendanceStatus): TrainingOutcome? = when (status) {
            AttendanceStatus.ATTENDED -> ATTENDED
            AttendanceStatus.SKIPPED -> SKIPPED
            AttendanceStatus.PLANNED, AttendanceStatus.CANCELLED -> null
        }
    }
}
```

Create `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * A permanent record of one confirmed training session.
 *
 * A [TrainingEvent] is a schedule entry and is meant to be disposable: you tidy a cluttered
 * calendar, you throw away a series you created by mistake. A training record is not. So this
 * row holds its session by plain id with no foreign key -- exactly as [ActivityEvent] holds
 * its target -- and keeps its own copies of the title, duration and style names. When the
 * session is deleted the record is stamped [orphanedAt] and stands on its own, unchanged for
 * good.
 *
 * One record per session, updated in place. Changing your mind from attended to skipped
 * rewrites this row rather than appending a correction, so statistics never have to window
 * down to the latest row per session.
 */
@Entity
@Table(name = "training_record")
class TrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    /**
     * The session this was recorded from. A plain id rather than a @ManyToOne: a foreign key
     * would either cascade the record away with its session or block the session's delete,
     * and the whole point of this table is that neither happens.
     */
    @Column(name = "training_event_id", nullable = false, unique = true, updatable = false)
    var trainingEventId: UUID? = null

    /** When the session happened. Snapshotted, so rescheduling a past session cannot move it. */
    @Column(name = "occurred_at", nullable = false)
    var occurredAt: LocalDateTime = LocalDateTime.now()

    /**
     * How long the session ran. Recorded rather than derived from a timestamp pair, so
     * rescheduling an already-attended session cannot quietly change historical hours.
     */
    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var outcome: TrainingOutcome = TrainingOutcome.ATTENDED

    /** Snapshotted so an orphaned record still reads as something recognisable. */
    @Column(nullable = false)
    var title: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: TrainingEventType = TrainingEventType.TRAINING

    @OneToMany(mappedBy = "trainingRecord", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var segments: MutableList<TrainingRecordSegment> = mutableListOf()

    /**
     * Restricts rather than cascades, following [ActivityEvent.actor]: a session's
     * `created_by_id` may cascade on user delete, but deleting a user must not silently take
     * their training history with it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    var createdBy: AppUser? = null

    /**
     * Stamped when the session is deleted. From that moment the record is frozen: it is read
     * entirely from its own snapshots, and nothing will ever update it again.
     */
    @Column(name = "orphaned_at")
    var orphanedAt: LocalDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()

    /** Its calendar session is gone. The record stands alone, and cannot be corrected in place. */
    val isOrphaned: Boolean
        get() = orphanedAt != null
}
```

Create `src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordSegment.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

/**
 * One style's share of a recorded session — the snapshot counterpart of
 * [TrainingEventSegment].
 *
 * It keeps both a live link to the category and a copy of the category's name at the time.
 * The link is what makes a rename relabel the history that belongs to that style instead of
 * splitting it in two; the copy is what is left to read once the category is deleted.
 */
@Entity
@Table(name = "training_record_segment")
class TrainingRecordSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_record_id", nullable = false)
    var trainingRecord: TrainingRecord? = null

    /**
     * Nullable on purpose, and ON DELETE SET NULL in the schema: a category may be deleted
     * long after the session happened, and that must cost the link rather than the record.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dance_category_id")
    var danceCategory: DanceCategory? = null

    @Column(name = "category_name", nullable = false)
    var categoryName: String = ""

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    /** The live category's name while it exists, the snapshot once it does not. */
    val label: String
        get() = danceCategory?.name ?: categoryName
}
```

- [ ] **Step 5: Write the repository**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt`:

```kotlin
package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.TrainingRecord
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingRecordRepository : JpaRepository<TrainingRecord, UUID> {

    /** The record of one session, if it has been confirmed. `training_event_id` is unique. */
    fun findByTrainingEventId(trainingEventId: UUID): TrainingRecord?

    /**
     * The records of a batch of sessions, for the bulk deletes that orphan a whole series at
     * once. Series generation is capped at 52 occurrences, so the `IN` list is always small.
     */
    fun findAllByTrainingEventIdIn(trainingEventIds: Collection<UUID>): List<TrainingRecord>

    /**
     * A user's whole confirmed history, newest first, with the style breakdown already
     * loaded. Both the statistics page and the history page walk every segment, so the
     * entity graph is what keeps this from issuing a query per record.
     */
    @EntityGraph(attributePaths = ["segments", "segments.danceCategory"])
    fun findAllByCreatedByOrderByOccurredAtDesc(createdBy: AppUser): List<TrainingRecord>
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.model.TrainingRecordTest"`
Expected: PASS, 3 tests.

- [ ] **Step 7: Verify nothing else broke**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V28__add_training_record.sql \
        src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingOutcome.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecord.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordSegment.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingRecordRepository.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/model/TrainingRecordTest.kt
git commit -m "Add the training record table, entities and backfill"
```

---

## Task 2: The writer that keeps a record in step with its session

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriter.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriterTest.kt`

**Interfaces:**
- Consumes: `TrainingRecord`, `TrainingRecordSegment`, `TrainingOutcome.from`, `TrainingRecordRepository` from Task 1.
- Produces: `@Component class TrainingRecordWriter(private val trainingRecordRepository: TrainingRecordRepository)` with `fun sync(event: TrainingEvent)` and `fun orphan(eventIds: Collection<UUID>)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriterTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventSegment
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyCollection
import org.mockito.Mockito.never
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.UUID

class TrainingRecordWriterTest {

    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var writer: TrainingRecordWriter
    private lateinit var owner: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        writer = TrainingRecordWriter(trainingRecordRepository)
        owner = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun event(
        status: AttendanceStatus,
        minutes: Long = 90,
        title: String = "Monday practice",
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            this.title = title
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = owner
            this.segments = segments.mapIndexed { index, (sliceCategory, sliceMinutes) ->
                TrainingEventSegment().apply {
                    danceCategory = sliceCategory
                    durationMinutes = sliceMinutes
                    sortOrder = index
                }
            }.toMutableList()
        }
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun savedRecord(): TrainingRecord {
        val captor = ArgumentCaptor.forClass(TrainingRecord::class.java)
        verify(trainingRecordRepository).save(captor.capture())
        return captor.value
    }

    @Test
    fun `marking a session attended writes a record snapshotting the session`() {
        val standard = category("Standard")
        val source = event(AttendanceStatus.ATTENDED, minutes = 120, segments = listOf(standard to 60))
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        val record = savedRecord()
        assertEquals(source.id, record.trainingEventId)
        assertEquals(TrainingOutcome.ATTENDED, record.outcome)
        assertEquals("Monday practice", record.title)
        assertEquals(120, record.durationMinutes)
        assertEquals(source.startTime, record.occurredAt)
        assertEquals(TrainingEventType.TRAINING, record.eventType)
        assertEquals(owner, record.createdBy)
        assertNull(record.orphanedAt)
        assertEquals(1, record.segments.size)
        assertEquals("Standard", record.segments.first().categoryName)
        assertEquals(60, record.segments.first().durationMinutes)
        assertEquals(standard, record.segments.first().danceCategory)
    }

    @Test
    fun `marking a session skipped writes a skipped record`() {
        val source = event(AttendanceStatus.SKIPPED)
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        assertEquals(TrainingOutcome.SKIPPED, savedRecord().outcome)
    }

    @Test
    fun `editing a confirmed session updates its record in place`() {
        val latin = category("Latin")
        val source = event(AttendanceStatus.ATTENDED, minutes = 60, title = "Retitled", segments = listOf(latin to 45))
        val existing = TrainingRecord().apply {
            id = UUID.randomUUID()
            trainingEventId = source.id
            outcome = TrainingOutcome.SKIPPED
            title = "Old title"
            durationMinutes = 30
            createdBy = owner
        }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        val record = savedRecord()
        assertEquals(existing.id, record.id, "the same row is rewritten, not a second one appended")
        assertEquals(TrainingOutcome.ATTENDED, record.outcome)
        assertEquals("Retitled", record.title)
        assertEquals(60, record.durationMinutes)
        assertEquals(listOf("Latin"), record.segments.map { it.categoryName })
    }

    @Test
    fun `marking a session back to planned removes its record`() {
        val source = event(AttendanceStatus.PLANNED)
        val existing = TrainingRecord().apply { trainingEventId = source.id }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository).delete(existing)
        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
    }

    @Test
    fun `marking a session cancelled removes its record`() {
        val source = event(AttendanceStatus.CANCELLED)
        val existing = TrainingRecord().apply { trainingEventId = source.id }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository).delete(existing)
    }

    @Test
    fun `a planned session with no record writes nothing`() {
        val source = event(AttendanceStatus.PLANNED)
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(null)

        writer.sync(source)

        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
        verify(trainingRecordRepository, never()).delete(any(TrainingRecord::class.java))
    }

    @Test
    fun `an orphaned record is frozen and never rewritten`() {
        val source = event(AttendanceStatus.ATTENDED, title = "Late edit")
        val existing = TrainingRecord().apply {
            trainingEventId = source.id
            title = "As recorded"
            orphanedAt = LocalDateTime.now()
        }
        `when`(trainingRecordRepository.findByTrainingEventId(source.id!!)).thenReturn(existing)

        writer.sync(source)

        verify(trainingRecordRepository, never()).save(any(TrainingRecord::class.java))
        assertEquals("As recorded", existing.title)
    }

    @Test
    fun `deleting sessions orphans their records instead of removing them`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val records = listOf(
            TrainingRecord().apply { trainingEventId = first },
            TrainingRecord().apply { trainingEventId = second }
        )
        `when`(trainingRecordRepository.findAllByTrainingEventIdIn(listOf(first, second))).thenReturn(records)

        writer.orphan(listOf(first, second))

        verify(trainingRecordRepository).saveAll(records)
        records.forEach { assertNotNull(it.orphanedAt) }
        assertTrue(records.all { it.isOrphaned })
    }

    @Test
    fun `orphaning an already-orphaned record does not restamp it`() {
        val id = UUID.randomUUID()
        val stamped = LocalDateTime.of(2026, 1, 1, 12, 0)
        val existing = TrainingRecord().apply {
            trainingEventId = id
            orphanedAt = stamped
        }
        `when`(trainingRecordRepository.findAllByTrainingEventIdIn(listOf(id))).thenReturn(listOf(existing))

        writer.orphan(listOf(id))

        // Left exactly as it was: a second delete must not move the date on which the
        // record's session disappeared.
        assertEquals(stamped, existing.orphanedAt)
    }

    @Test
    fun `orphaning nothing touches the database not at all`() {
        writer.orphan(emptyList())

        verify(trainingRecordRepository, never()).findAllByTrainingEventIdIn(anyCollection())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingRecordWriterTest"`
Expected: FAIL — compilation error, `TrainingRecordWriter` unresolved.

- [ ] **Step 3: Write the writer**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriter.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * Keeps a session's training record in step with the session.
 *
 * Not @Transactional and not a service: it is called from inside the transactional
 * persistence beans so a record commits with the session write that caused it. The activity
 * feed is written from an after-commit listener and can afford to lose a row; statistics
 * cannot, so this deliberately does not go the same way.
 */
@Component
class TrainingRecordWriter(
    private val trainingRecordRepository: TrainingRecordRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingRecordWriter::class.java)
    }

    /**
     * Writes, updates or removes the record for [event] according to its attendance status.
     *
     * Attended and skipped are confirmed outcomes and get a record; planned and cancelled do
     * not, and marking a session back to either removes the record — that is a correction of
     * a mis-mark, not a piece of history.
     *
     * An orphaned record is left alone: its session is gone, so anything arriving here for it
     * would be a write against something that no longer exists.
     */
    fun sync(event: TrainingEvent) {
        val eventId = requireNotNull(event.id) {
            "A training event must be saved before its record can be written"
        }
        val existing = trainingRecordRepository.findByTrainingEventId(eventId)
        val outcome = TrainingOutcome.from(event.attendanceStatus)

        if (outcome == null) {
            existing?.let {
                log.debug("Session {} is no longer confirmed; removing its training record", eventId)
                trainingRecordRepository.delete(it)
            }
            return
        }

        if (existing != null && existing.isOrphaned) {
            log.warn("Training record for session {} is orphaned; leaving it frozen", eventId)
            return
        }

        val record = existing ?: TrainingRecord().apply {
            trainingEventId = eventId
            createdBy = event.createdBy
            createdAt = LocalDateTime.now()
        }
        record.occurredAt = event.startTime
        record.durationMinutes = event.durationMinutes.toInt()
        record.outcome = outcome
        record.title = event.title
        record.eventType = event.eventType
        record.updatedAt = LocalDateTime.now()
        applySegments(record, event)

        trainingRecordRepository.save(record)
    }

    /**
     * Stamps the records of deleted sessions as orphaned. The rows stay — that is the entire
     * point of the table — and from here on they are frozen and read from their own snapshots.
     *
     * Records already stamped are left untouched, so a second delete cannot move the date on
     * which a record's session disappeared.
     */
    fun orphan(eventIds: Collection<UUID>) {
        if (eventIds.isEmpty()) return

        val records = trainingRecordRepository.findAllByTrainingEventIdIn(eventIds)
            .filterNot { it.isOrphaned }
        if (records.isEmpty()) return

        val orphanedAt = LocalDateTime.now()
        records.forEach { it.orphanedAt = orphanedAt }
        trainingRecordRepository.saveAll(records)
        log.debug("Orphaned {} training records across {} deleted sessions", records.size, eventIds.size)
    }

    /**
     * Rebuilds the snapshot of the style breakdown in place.
     *
     * orphanRemoval on the collection means clearing and refilling the existing list deletes
     * the rows that went away — the same reason `TrainingEventServiceImpl.applySegments`
     * refills rather than replaces the list instance.
     */
    private fun applySegments(record: TrainingRecord, event: TrainingEvent) {
        record.segments.clear()
        event.segments.forEachIndexed { index, source ->
            val category = source.danceCategory ?: return@forEachIndexed
            record.segments.add(TrainingRecordSegment().apply {
                trainingRecord = record
                danceCategory = category
                categoryName = category.name
                durationMinutes = source.durationMinutes
                sortOrder = index
            })
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingRecordWriterTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriter.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingRecordWriterTest.kt
git commit -m "Add TrainingRecordWriter, which keeps a record in step with its session"
```

---

## Task 3: Wire the writer into the transactional persistence beans

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistence.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistence.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistenceTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistenceTest.kt`

**Interfaces:**
- Consumes: `TrainingRecordWriter.sync(event)` and `TrainingRecordWriter.orphan(eventIds)` from Task 2.
- Produces: `TrainingEventPersistence(trainingEventRepository, trainingRecordWriter, eventPublisher)` and `TrainingSeriesPersistence(trainingSeriesRepository, trainingEventRepository, trainingRecordWriter, eventPublisher)` — both constructors gain `trainingRecordWriter` as their second-to-last parameter, before `eventPublisher`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistenceTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.UUID

/**
 * The record has to be written by the same transaction as the session, which is what this
 * bean is. TrainingEventServiceTest mocks this bean out entirely, so without these the
 * wiring would be untested.
 */
class TrainingEventPersistenceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordWriter: TrainingRecordWriter
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var persistence: TrainingEventPersistence
    private lateinit var actor: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordWriter = mock(TrainingRecordWriter::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        persistence = TrainingEventPersistence(trainingEventRepository, trainingRecordWriter, eventPublisher)
        actor = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun event(status: AttendanceStatus = AttendanceStatus.ATTENDED): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            startTime = start
            endTime = start.plusMinutes(90)
            attendanceStatus = status
            createdBy = actor
        }
    }

    @Test
    fun `inserting a session syncs its record`() {
        val toSave = event()
        `when`(trainingEventRepository.save(toSave)).thenReturn(toSave)

        persistence.insert(toSave, actor)

        verify(trainingRecordWriter).sync(toSave)
    }

    @Test
    fun `updating a session syncs its record`() {
        val toSave = event(AttendanceStatus.SKIPPED)
        `when`(trainingEventRepository.save(toSave)).thenReturn(toSave)

        persistence.applyUpdate(toSave, actor)

        verify(trainingRecordWriter).sync(toSave)
    }

    @Test
    fun `deleting a session orphans its record before the row goes`() {
        val toDelete = event()

        persistence.remove(toDelete, actor)

        // Order matters only for readability -- there is no foreign key between the two --
        // but reading the orphan first keeps the intent obvious.
        val order: InOrder = inOrder(trainingRecordWriter, trainingEventRepository)
        order.verify(trainingRecordWriter).orphan(listOf(toDelete.id!!))
        order.verify(trainingEventRepository).delete(toDelete)
    }
}
```

Create `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistenceTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingSeries
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingSeriesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.UUID

/**
 * The two bulk series methods publish no domain event, so nothing else would notice that a
 * series delete had taken a month of recorded training with it. These are that check.
 */
class TrainingSeriesPersistenceTest {

    private lateinit var trainingSeriesRepository: TrainingSeriesRepository
    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordWriter: TrainingRecordWriter
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var persistence: TrainingSeriesPersistence
    private lateinit var actor: AppUser

    @BeforeEach
    fun setUp() {
        trainingSeriesRepository = mock(TrainingSeriesRepository::class.java)
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordWriter = mock(TrainingRecordWriter::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        persistence = TrainingSeriesPersistence(
            trainingSeriesRepository, trainingEventRepository, trainingRecordWriter, eventPublisher
        )
        actor = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
        }
    }

    private fun occurrence(): TrainingEvent {
        val start = LocalDateTime.of(2026, 9, 7, 18, 0)
        return TrainingEvent().apply {
            id = UUID.randomUUID()
            title = "Monday practice"
            startTime = start
            endTime = start.plusMinutes(90)
            attendanceStatus = AttendanceStatus.ATTENDED
            createdBy = actor
        }
    }

    @Test
    fun `deleting occurrences in bulk orphans their records`() {
        val first = occurrence()
        val second = occurrence()

        persistence.removeOccurrences(listOf(first, second))

        verify(trainingRecordWriter).orphan(listOf(first.id!!, second.id!!))
        verify(trainingEventRepository).deleteAll(listOf(first, second))
    }

    @Test
    fun `regenerating a series orphans the records of the occurrences it replaces`() {
        val series = TrainingSeries().apply { id = UUID.randomUUID() }
        val removed = occurrence()
        val added = occurrence()
        `when`(trainingEventRepository.saveAll(anyList())).thenReturn(mutableListOf(added))

        persistence.replaceOccurrences(series, listOf(removed), listOf(added))

        verify(trainingRecordWriter).orphan(listOf(removed.id!!))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.Training*PersistenceTest"`
Expected: FAIL — the constructors do not take a `TrainingRecordWriter`.

- [ ] **Step 3: Wire up `TrainingEventPersistence`**

Replace the body of `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistence.kt` below the KDoc header with:

```kotlin
@Component
class TrainingEventPersistence(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordWriter: TrainingRecordWriter,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun insert(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        // In this transaction, not in an after-commit listener: statistics are read from the
        // record, and a lost record is a lost hour of training.
        trainingRecordWriter.sync(saved)
        eventPublisher.publishEvent(TrainingEventCreatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun applyUpdate(event: TrainingEvent, actor: AppUser): TrainingEvent {
        val saved = trainingEventRepository.save(event)
        trainingRecordWriter.sync(saved)
        eventPublisher.publishEvent(TrainingEventUpdatedEvent(saved, actor))
        return saved
    }

    @Transactional
    fun remove(event: TrainingEvent, actor: AppUser) {
        val id: UUID = event.id!!
        val title = event.title
        // The record is marked orphaned rather than deleted: the schedule entry is disposable,
        // the training that happened is not.
        trainingRecordWriter.orphan(listOf(id))
        trainingEventRepository.delete(event)
        eventPublisher.publishEvent(TrainingEventDeletedEvent(id, title, actor))
    }
}
```

Also extend the class KDoc's final paragraph by appending:

```
 * It is also where a session's training record is written, for the same transactional
 * reason: the record must commit with the session or not at all.
```

- [ ] **Step 4: Wire up `TrainingSeriesPersistence`**

In `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistence.kt`, add the writer to the constructor:

```kotlin
@Component
class TrainingSeriesPersistence(
    private val trainingSeriesRepository: TrainingSeriesRepository,
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordWriter: TrainingRecordWriter,
    private val eventPublisher: ApplicationEventPublisher
) {
```

Add this comment line just above `val savedSeries = trainingSeriesRepository.save(series)` inside `insertSeries`:

```kotlin
        // No records to write: every generated occurrence starts PLANNED, and an unconfirmed
        // session is not history. They get records when the user confirms them one by one.
```

Replace `replaceOccurrences` and `removeOccurrences` with:

```kotlin
    /** Replaces the future occurrences of a series in one transaction. */
    @Transactional
    fun replaceOccurrences(
        series: TrainingSeries,
        removed: List<TrainingEvent>,
        added: List<TrainingEvent>
    ): List<TrainingEvent> {
        trainingSeriesRepository.save(series)
        // "This and following" can cut across sessions the user already confirmed. Their
        // records outlive the regeneration rather than vanishing with the old occurrences.
        trainingRecordWriter.orphan(removed.mapNotNull { it.id })
        trainingEventRepository.deleteAll(removed)
        added.forEach { it.series = series }
        return trainingEventRepository.saveAll(added)
    }

    @Transactional
    fun removeOccurrences(occurrences: List<TrainingEvent>) {
        trainingRecordWriter.orphan(occurrences.mapNotNull { it.id })
        trainingEventRepository.deleteAll(occurrences)
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.Training*PersistenceTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Verify the services that use these beans still pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingEventServiceTest" --tests "com.jankowski.rafal.dancebook.service.TrainingSeriesServiceTest"`
Expected: PASS — both mock the persistence beans, so neither should need changing.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistence.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistence.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingEventPersistenceTest.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingSeriesPersistenceTest.kt
git commit -m "Write training records in the same transaction as the session write"
```

---

## Task 4: Statistics read history from records

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt` (remove `findAllByCreatedBy`)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt` (rewrite the harness, keep every assertion)

**Interfaces:**
- Consumes: `TrainingRecord`, `TrainingOutcome`, `TrainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc` from Task 1.
- Produces: `TrainingStatsServiceImpl(trainingEventRepository, trainingRecordRepository, appUserService)` — the record repository is the new second parameter. `TrainingStats`, `SessionCounts` and `BreakdownSlice` are unchanged, so no caller or template changes.

- [ ] **Step 1: Rewrite the test harness so the same behaviours are driven through records**

Replace the whole of `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt` with the following. Every existing assertion is preserved; what changes is that a confirmed fixture now produces a record as well as an event, and three new tests cover the record-specific behaviour.

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.formatMinutes
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.LocalDateTime
import java.util.UUID

/**
 * The dashboard is deliberately hybrid: history figures come from training records, schedule
 * figures from calendar events. The fixture below mirrors that — a confirmed session produces
 * both an event and its record, exactly as the write path does — so every one of these
 * assertions still describes a real state of the database.
 */
class TrainingStatsServiceTest {

    private lateinit var trainingEventRepository: TrainingEventRepository
    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingStatsService: TrainingStatsServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingEventRepository = mock(TrainingEventRepository::class.java)
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            displayName = "Test User"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingStatsService = TrainingStatsServiceImpl(
            trainingEventRepository, trainingRecordRepository, appUserService
        )
    }

    /**
     * One session as the database actually holds it: a calendar row, plus a record when the
     * session has been confirmed. [record] alone models an orphaned record — a session that
     * was deleted after being confirmed.
     */
    private class Session(val event: TrainingEvent?, val record: TrainingRecord?)

    /**
     * @param daysAgo negative values put the session in the future.
     */
    private fun session(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): Session {
        val start = LocalDateTime.now().minusDays(daysAgo)
        val event = TrainingEvent().apply {
            id = UUID.randomUUID()
            startTime = start
            endTime = start.plusMinutes(minutes)
            attendanceStatus = status
            eventType = type
            createdBy = currentUser
        }
        val outcome = TrainingOutcome.from(status)
        val record = outcome?.let { confirmed ->
            recordOf(event.id!!, start, minutes, confirmed, type, segments)
        }
        return Session(event, record)
    }

    /** A record whose session has been deleted: history with no schedule entry behind it. */
    private fun orphanedSession(
        daysAgo: Long,
        status: AttendanceStatus,
        minutes: Long = 60,
        type: TrainingEventType = TrainingEventType.TRAINING,
        segments: List<Pair<DanceCategory, Int>> = emptyList()
    ): Session {
        val start = LocalDateTime.now().minusDays(daysAgo)
        val outcome = requireNotNull(TrainingOutcome.from(status)) {
            "Only a confirmed session can leave an orphaned record behind"
        }
        val record = recordOf(UUID.randomUUID(), start, minutes, outcome, type, segments).apply {
            orphanedAt = LocalDateTime.now()
        }
        return Session(event = null, record = record)
    }

    private fun recordOf(
        eventId: UUID,
        start: LocalDateTime,
        minutes: Long,
        outcome: TrainingOutcome,
        type: TrainingEventType,
        segments: List<Pair<DanceCategory, Int>>
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = eventId
        occurredAt = start
        durationMinutes = minutes.toInt()
        this.outcome = outcome
        title = "Session"
        eventType = type
        createdBy = currentUser
        // The back-reference to the parent record is left unset: nothing in the statistics
        // service reads it, and setting it here would only be ceremony.
        this.segments = segments.mapIndexed { index, (sliceCategory, sliceMinutes) ->
            TrainingRecordSegment().apply {
                danceCategory = sliceCategory
                categoryName = sliceCategory.name
                durationMinutes = sliceMinutes
                sortOrder = index
            }
        }.toMutableList()
    }

    private fun category(name: String) = DanceCategory().apply {
        id = UUID.randomUUID()
        this.name = name
    }

    private fun given(vararg sessions: Session) {
        `when`(trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(currentUser))
            .thenReturn(sessions.mapNotNull { it.event })
        `when`(trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser))
            .thenReturn(sessions.mapNotNull { it.record }.sortedByDescending { it.occurredAt })
    }

    @Test
    fun `hours count attended sessions only`() {
        given(
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 90),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED, minutes = 60),
            session(daysAgo = -2, status = AttendanceStatus.PLANNED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(90L, stats.totalMinutesTrained)
        assertEquals("1h 30m", stats.totalTrainedLabel)
    }

    @Test
    fun `every session lands in exactly one count bucket`() {
        given(
            session(daysAgo = -2, status = AttendanceStatus.PLANNED),
            session(daysAgo = 2, status = AttendanceStatus.PLANNED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED)
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
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED),
            session(daysAgo = 6, status = AttendanceStatus.PLANNED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(75, stats.attendanceRatePercent)
    }

    @Test
    fun `attendance rate is null when nothing has been decided`() {
        given(
            session(daysAgo = -1, status = AttendanceStatus.PLANNED),
            session(daysAgo = 5, status = AttendanceStatus.CANCELLED)
        )

        assertNull(trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `attendance rate rounds half up`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 5, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 6, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 7, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 8, status = AttendanceStatus.SKIPPED)
        )

        // 5/8 = 62.5% exactly, which must round up rather than down.
        assertEquals(63, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).attendanceRatePercent)
    }

    @Test
    fun `the period excludes sessions that start before its earliest day`() {
        given(
            session(daysAgo = 10, status = AttendanceStatus.ATTENDED, minutes = 60),
            session(daysAgo = 100, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(60L, stats.totalMinutesTrained)
        assertEquals(1, stats.counts.total)
    }

    @Test
    fun `a narrowed period still counts upcoming sessions`() {
        given(session(daysAgo = -5, status = AttendanceStatus.PLANNED))

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS).counts.upcoming)
    }

    @Test
    fun `durations format as hours and minutes`() {
        assertEquals("45m", formatMinutes(45))
        assertEquals("2h", formatMinutes(120))
        assertEquals("3h 15m", formatMinutes(195))
        assertEquals("0m", formatMinutes(0))
    }

    @Test
    fun `the streak counts attended sessions back to the first skip`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 3, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `cancelled and unconfirmed sessions do not break the streak`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 2, status = AttendanceStatus.CANCELLED),
            session(daysAgo = 3, status = AttendanceStatus.PLANNED),
            session(daysAgo = 4, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(2, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `an upcoming session does not break the streak`() {
        given(
            session(daysAgo = -3, status = AttendanceStatus.PLANNED),
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(1, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the streak spans full history even when the period is narrowed`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 100, status = AttendanceStatus.ATTENDED),
            session(daysAgo = 200, status = AttendanceStatus.ATTENDED)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.LAST_30_DAYS)

        assertEquals(1, stats.counts.total)
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `the streak is zero when the most recent decided session was skipped`() {
        given(
            session(daysAgo = 1, status = AttendanceStatus.SKIPPED),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED)
        )

        assertEquals(0, trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).currentStreak)
    }

    @Test
    fun `the this-year period excludes sessions from a previous year`() {
        given(
            session(daysAgo = 0, status = AttendanceStatus.ATTENDED, minutes = 60),
            session(daysAgo = 400, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.THIS_YEAR)

        assertEquals(1, stats.counts.total)
        assertEquals(60L, stats.totalMinutesTrained)
    }

    @Test
    fun `category minutes come from segments of attended sessions`() {
        val standard = category("Standard")
        val latin = category("Latin")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60, latin to 60)
            ),
            session(
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
            session(
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
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 90)
            ),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60)
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
            session(
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
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60, type = TrainingEventType.TRAINING),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90, type = TrainingEventType.TRAINING),
            session(daysAgo = 3, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            session(daysAgo = 4, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val byType = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byEventType

        assertEquals(listOf("TRAINING", "CAMP"), byType.map { it.label })
        assertEquals(150L, byType.first().minutes)
        assertEquals(2, byType.first().sessionCount)
        assertEquals(240L, byType.last().minutes)
    }

    @Test
    fun `every slice carries a colour, and the unassigned slice always the palette's unassigned colour`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 120,
                segments = listOf(standard to 60)
            )
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        (stats.byCategory + stats.byEventType).forEach { slice ->
            assertTrue(slice.color.startsWith("#"), "slice ${slice.label} has no colour")
        }
        val unassigned = stats.byCategory.first { it.label == "Unassigned" }
        assertEquals(TrainingEventPalette.UNASSIGNED_COLOR, unassigned.color)
    }

    @Test
    fun `event type minutes reconcile with the total minutes trained`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 90,
                type = TrainingEventType.TRAINING, segments = listOf(standard to 60)
            ),
            session(daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 240, type = TrainingEventType.CAMP),
            session(daysAgo = 3, status = AttendanceStatus.SKIPPED, minutes = 60, type = TrainingEventType.WORKSHOP)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(stats.totalMinutesTrained, stats.byEventType.sumOf { it.minutes })
    }

    @Test
    fun `deleting a confirmed session leaves its hours and its streak standing`() {
        val standard = category("Standard")
        given(
            orphanedSession(
                daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 90,
                segments = listOf(standard to 90)
            ),
            session(daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60)
        )

        val stats = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME)

        assertEquals(150L, stats.totalMinutesTrained)
        assertEquals(2, stats.counts.attended)
        assertEquals(2, stats.currentStreak)
        assertEquals(90L, stats.byCategory.first { it.label == "Standard" }.minutes)
    }

    @Test
    fun `renaming a category relabels its history instead of splitting it in two`() {
        val standard = category("Standard")
        given(
            session(
                daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            ),
            session(
                daysAgo = 2, status = AttendanceStatus.ATTENDED, minutes = 60,
                segments = listOf(standard to 60)
            )
        )
        // Renamed after the older session was recorded: the slices must still be one style.
        standard.name = "Ballroom"

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Ballroom"), byCategory.map { it.label })
        assertEquals(120L, byCategory.single().minutes)
        assertEquals(2, byCategory.single().sessionCount)
    }

    @Test
    fun `a deleted category keeps the name it had when the session was recorded`() {
        val standard = category("Standard")
        val session = session(
            daysAgo = 1, status = AttendanceStatus.ATTENDED, minutes = 60,
            segments = listOf(standard to 60)
        )
        // Deleting the category nulls the link, which is what ON DELETE SET NULL leaves behind.
        session.record!!.segments.forEach { it.danceCategory = null }
        given(session)

        val byCategory = trainingStatsService.statsForCurrentUser(StatsPeriod.ALL_TIME).byCategory

        assertEquals(listOf("Standard"), byCategory.map { it.label })
        assertEquals(60L, byCategory.single().minutes)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: FAIL — `TrainingStatsServiceImpl` takes two constructor arguments, not three.

- [ ] **Step 3: Rewrite the statistics service**

Replace the whole of `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt` with:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BreakdownSlice
import com.jankowski.rafal.dancebook.dto.SessionCounts
import com.jankowski.rafal.dancebook.dto.StatsPeriod
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingStats
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Every figure on the training dashboard.
 *
 * Deliberately hybrid, and the split is the point. Hours, the attended and skipped counts,
 * the attendance rate, the streak and both breakdowns are facts about *history* and come from
 * `training_record`, which outlives the sessions it describes — so tidying the calendar no
 * longer rewrites the past. Upcoming, unconfirmed and cancelled are facts about the
 * *schedule* and stay on `training_event`, because they have no business outliving the
 * sessions they describe.
 *
 * The whole of the user's history is loaded once and reduced in memory: the streak spans all
 * of it regardless of the selected period, the volumes are tiny, and a sequential walk is far
 * clearer here than the SQL that would express it.
 */
@Service
@Transactional(readOnly = true)
class TrainingStatsServiceImpl(
    private val trainingEventRepository: TrainingEventRepository,
    private val trainingRecordRepository: TrainingRecordRepository,
    private val appUserService: AppUserService
) : TrainingStatsService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingStatsServiceImpl::class.java)
        /** Shown as its own slice, so the chart reconciles with the hours card. */
        private const val UNASSIGNED_LABEL = "Unassigned"
    }

    override fun statsForCurrentUser(period: StatsPeriod): TrainingStats {
        val currentUser = appUserService.getCurrentUser()
        val allRecords = trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser)
        val allEvents = trainingEventRepository.findAllByCreatedByOrderByStartTimeDesc(currentUser)
        log.debug("Computing {} training stats for user '{}'", period, currentUser.username)

        // One "today" for the whole computation: a render straddling midnight must not judge
        // some sessions against one day and the rest against the next.
        val today = LocalDate.now()
        val recordsInPeriod = allRecords.filter { period.contains(it.occurredAt, today) }
        val eventsInPeriod = allEvents.filter { period.contains(it.startTime, today) }
        val attended = recordsInPeriod.filter { it.outcome == TrainingOutcome.ATTENDED }
        val counts = countsOf(recordsInPeriod, eventsInPeriod)

        return TrainingStats(
            period = period,
            totalMinutesTrained = attended.sumOf { it.durationMinutes.toLong() },
            counts = counts,
            attendanceRatePercent = attendanceRate(counts),
            currentStreak = currentStreak(allRecords),
            byCategory = categoryBreakdown(attended),
            byEventType = eventTypeBreakdown(attended)
        )
    }

    /**
     * The hybrid in one method: the two confirmed buckets are counted from records, the three
     * schedule buckets from events.
     *
     * A confirmed event is skipped here rather than counted, because its record already counts
     * it — counting both would double every attended session. Unconfirmed is still tested
     * first, so a past session still marked planned reads as needing confirmation rather than
     * as upcoming, the same precedence `TrainingEventPalette` applies when it picks a colour.
     *
     * The buckets therefore stay disjoint: a session appears either as a record or as an
     * event, never as both, and an orphaned record appears with no event at all.
     */
    private fun countsOf(records: List<TrainingRecord>, events: List<TrainingEvent>): SessionCounts {
        var upcoming = 0
        var unconfirmed = 0
        var cancelled = 0
        for (event in events) {
            when {
                event.isAwaitingConfirmation -> unconfirmed++
                event.attendanceStatus == AttendanceStatus.ATTENDED -> Unit
                event.attendanceStatus == AttendanceStatus.SKIPPED -> Unit
                event.attendanceStatus == AttendanceStatus.CANCELLED -> cancelled++
                else -> upcoming++
            }
        }
        return SessionCounts(
            upcoming = upcoming,
            unconfirmed = unconfirmed,
            attended = records.count { it.outcome == TrainingOutcome.ATTENDED },
            skipped = records.count { it.outcome == TrainingOutcome.SKIPPED },
            cancelled = cancelled
        )
    }

    /** Null rather than zero when nothing has been decided: no data is not a bad record. */
    private fun attendanceRate(counts: SessionCounts): Int? {
        val decided = counts.attended + counts.skipped
        if (decided == 0) return null
        return (counts.attended * 100.0 / decided).roundToInt()
    }

    /**
     * Consecutive attended sessions counting back from the most recent.
     *
     * Records hold only confirmed outcomes, so this is now exactly what it claims to be: there
     * is no cancelled or unconfirmed session to step over, because neither is history. Only a
     * skip ends the run.
     *
     * Walks the user's whole history rather than the selected period: a streak cut off at a
     * window boundary would report a number that is not the user's streak.
     */
    private fun currentStreak(allRecords: List<TrainingRecord>): Int {
        var streak = 0
        for (record in allRecords.sortedByDescending { it.occurredAt }) {
            when (record.outcome) {
                TrainingOutcome.ATTENDED -> streak++
                TrainingOutcome.SKIPPED -> return streak
            }
        }
        return streak
    }

    /**
     * A slice of the style breakdown, identified the way the record identifies its category.
     *
     * Keyed on the category's id while the category exists, so renaming a style relabels all
     * of its history instead of splitting it into a before and an after — the bug the old
     * name-keyed breakdown had, which has already bitten this codebase once. Once the category
     * is deleted the id is gone and the snapshotted name is all there is to key on.
     */
    private data class CategoryKey(val id: UUID?, val label: String)

    private fun keyOf(segment: TrainingRecordSegment): CategoryKey {
        val live = segment.danceCategory
        return if (live != null) CategoryKey(live.id, live.name) else CategoryKey(null, segment.categoryName)
    }

    /**
     * Style time across attended sessions, with everything left over gathered into a trailing
     * "Unassigned" slice.
     *
     * Segments are optional and may cover less than a session's recorded length — warm-ups and
     * breaks — while competitions and camps usually carry none at all. Without the remainder
     * slice the chart would total fewer hours than the card above it claims.
     */
    private fun categoryBreakdown(attended: List<TrainingRecord>): List<BreakdownSlice> {
        val minutesByCategory = mutableMapOf<CategoryKey, Long>()
        val sessionsByCategory = mutableMapOf<CategoryKey, Int>()

        for (record in attended) {
            val categoriesTouched = mutableSetOf<CategoryKey>()
            for (segment in record.segments) {
                val key = keyOf(segment)
                minutesByCategory.merge(key, segment.durationMinutes.toLong(), Long::plus)
                categoriesTouched += key
            }
            categoriesTouched.forEach { sessionsByCategory.merge(it, 1, Int::plus) }
        }

        val slices = minutesByCategory.keys.sortedBy { it.label }.mapIndexed { index, key ->
            BreakdownSlice(
                label = key.label,
                minutes = minutesByCategory.getValue(key),
                sessionCount = sessionsByCategory[key] ?: 0,
                color = TrainingEventPalette.chartColor(index)
            )
        }

        // Negative is unreachable: TrainingEventServiceImpl (applySegments and reschedule)
        // enforces at write time that segment minutes never exceed a session's wall clock, and
        // the record copies both, so this guard only ever discards the zero case.
        val unassigned = attended.sumOf { it.durationMinutes.toLong() } - minutesByCategory.values.sum()
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
     * reshuffle as the data changes. Labels are the raw enum names, matching how the training
     * list already renders an event's type.
     */
    private fun eventTypeBreakdown(attended: List<TrainingRecord>): List<BreakdownSlice> =
        attended.groupBy { it.eventType }
            .toList()
            .sortedBy { (type, _) -> type.ordinal }
            .mapIndexed { index, (type, records) ->
                BreakdownSlice(
                    label = type.name,
                    minutes = records.sumOf { it.durationMinutes.toLong() },
                    sessionCount = records.size,
                    color = TrainingEventPalette.chartColor(index)
                )
            }
}
```

- [ ] **Step 4: Drop the now-unused repository method**

The statistics page was the only caller of `findAllByCreatedBy`, and it no longer reads event segments at all. Delete this block from `src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt`:

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

`@EntityGraph` is still used by `findAllByIdIn` further down, so the import stays.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingStatsServiceTest"`
Expected: PASS, 21 tests — the original 18 plus the three new record-specific ones.

- [ ] **Step 6: Verify the stats page still renders**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.TrainingStatsViewRenderingTest"`
Expected: PASS — it mocks `TrainingStatsService`, and the DTOs are unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingStatsServiceTest.kt
git commit -m "Compute training history statistics from records, not calendar events"
```

---

## Task 5: The history read model

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingHistory.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryService.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingEventPalette.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceTest.kt`

**Interfaces:**
- Consumes: `TrainingRecord`, `TrainingOutcome`, `TrainingRecordRepository` from Task 1; `formatMinutes(Long): String` and `TrainingEventPalette.Swatch` from existing `dto/`.
- Produces:
  - `TrainingEventPalette.swatchFor(outcome: TrainingOutcome): Swatch`
  - `data class TrainingHistoryRow(val record: TrainingRecord, val swatch: TrainingEventPalette.Swatch)` with `val isOrphaned: Boolean`
  - `data class TrainingHistoryMonth(val label: String, val rows: List<TrainingHistoryRow>, val attendedMinutes: Long)` with `sessionCount`, `sessionLabel`, `totalLabel`
  - `data class TrainingHistory(val months: List<TrainingHistoryMonth>)` with `isEmpty`, `orphanedCount`
  - `interface TrainingHistoryService { fun historyForCurrentUser(): TrainingHistory; fun deleteOrphanedRecord(recordId: UUID) }`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class TrainingHistoryServiceTest {

    private lateinit var trainingRecordRepository: TrainingRecordRepository
    private lateinit var appUserService: AppUserService
    private lateinit var trainingHistoryService: TrainingHistoryServiceImpl
    private lateinit var currentUser: AppUser

    @BeforeEach
    fun setUp() {
        trainingRecordRepository = mock(TrainingRecordRepository::class.java)
        appUserService = mock(AppUserService::class.java)

        currentUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "tester"
            role = Role.USER
        }
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        trainingHistoryService = TrainingHistoryServiceImpl(trainingRecordRepository, appUserService)
    }

    private fun record(
        occurredAt: LocalDateTime,
        outcome: TrainingOutcome = TrainingOutcome.ATTENDED,
        minutes: Int = 60,
        owner: AppUser = currentUser,
        orphaned: Boolean = false
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = UUID.randomUUID()
        this.occurredAt = occurredAt
        durationMinutes = minutes
        this.outcome = outcome
        title = "Monday practice"
        eventType = TrainingEventType.TRAINING
        createdBy = owner
        if (orphaned) orphanedAt = LocalDateTime.now()
    }

    private fun given(vararg records: TrainingRecord) {
        `when`(trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser))
            .thenReturn(records.toList())
    }

    @Test
    fun `history is grouped into months, newest first`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0)),
            record(LocalDateTime.of(2026, 9, 3, 18, 0)),
            record(LocalDateTime.of(2026, 8, 27, 18, 0))
        )

        val months = trainingHistoryService.historyForCurrentUser().months

        assertEquals(listOf("September 2026", "August 2026"), months.map { it.label })
        assertEquals(2, months.first().sessionCount)
        assertEquals("2 sessions", months.first().sessionLabel)
        assertEquals("1 session", months.last().sessionLabel)
    }

    @Test
    fun `a month totals the hours actually trained, not the skipped ones`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), TrainingOutcome.ATTENDED, minutes = 90),
            record(LocalDateTime.of(2026, 9, 3, 18, 0), TrainingOutcome.SKIPPED, minutes = 120)
        )

        val month = trainingHistoryService.historyForCurrentUser().months.single()

        assertEquals(90L, month.attendedMinutes)
        assertEquals("1h 30m", month.totalLabel)
    }

    @Test
    fun `each row carries the palette swatch for its outcome`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), TrainingOutcome.ATTENDED),
            record(LocalDateTime.of(2026, 9, 3, 18, 0), TrainingOutcome.SKIPPED)
        )

        val rows = trainingHistoryService.historyForCurrentUser().months.single().rows

        assertEquals(TrainingEventPalette.ATTENDED, rows.first().swatch)
        assertEquals(TrainingEventPalette.SKIPPED, rows.last().swatch)
    }

    @Test
    fun `rows whose session has been deleted are marked orphaned and counted`() {
        given(
            record(LocalDateTime.of(2026, 9, 10, 18, 0), orphaned = true),
            record(LocalDateTime.of(2026, 9, 3, 18, 0))
        )

        val history = trainingHistoryService.historyForCurrentUser()

        assertTrue(history.months.single().rows.first().isOrphaned)
        assertFalse(history.months.single().rows.last().isOrphaned)
        assertEquals(1, history.orphanedCount)
    }

    @Test
    fun `an empty history reports itself empty`() {
        given()

        assertTrue(trainingHistoryService.historyForCurrentUser().isEmpty)
    }

    @Test
    fun `an orphaned record can be removed`() {
        val orphan = record(LocalDateTime.of(2026, 9, 10, 18, 0), orphaned = true)
        `when`(trainingRecordRepository.findById(orphan.id!!)).thenReturn(Optional.of(orphan))

        trainingHistoryService.deleteOrphanedRecord(orphan.id!!)

        verify(trainingRecordRepository).delete(orphan)
    }

    @Test
    fun `a record whose session still exists cannot be removed here`() {
        val linked = record(LocalDateTime.of(2026, 9, 10, 18, 0))
        `when`(trainingRecordRepository.findById(linked.id!!)).thenReturn(Optional.of(linked))

        assertThrows(IllegalStateException::class.java) {
            trainingHistoryService.deleteOrphanedRecord(linked.id!!)
        }
        verify(trainingRecordRepository, never()).delete(linked)
    }

    @Test
    fun `another user's record cannot be removed`() {
        val stranger = AppUser().apply {
            id = UUID.randomUUID()
            username = "someone-else"
        }
        val theirs = record(LocalDateTime.of(2026, 9, 10, 18, 0), owner = stranger, orphaned = true)
        `when`(trainingRecordRepository.findById(theirs.id!!)).thenReturn(Optional.of(theirs))

        assertThrows(IllegalStateException::class.java) {
            trainingHistoryService.deleteOrphanedRecord(theirs.id!!)
        }
        verify(trainingRecordRepository, never()).delete(theirs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingHistoryServiceTest"`
Expected: FAIL — compilation error, `TrainingHistoryServiceImpl` unresolved.

- [ ] **Step 3: Add the outcome overload to the palette**

In `src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingEventPalette.kt`, add the import:

```kotlin
import com.jankowski.rafal.dancebook.model.TrainingOutcome
```

and add this function directly below the existing `swatchFor(event: TrainingEvent)`:

```kotlin
    /**
     * The colour of a recorded outcome.
     *
     * An orphaned record has no session left to ask, so it cannot go through the event
     * overload — but it must still come out the same colour the session had.
     */
    fun swatchFor(outcome: TrainingOutcome): Swatch = when (outcome) {
        TrainingOutcome.ATTENDED -> ATTENDED
        TrainingOutcome.SKIPPED -> SKIPPED
    }
```

- [ ] **Step 4: Write the DTOs**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingHistory.kt`:

```kotlin
package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.TrainingRecord

/**
 * One recorded session as the history page draws it.
 *
 * The swatch travels with the record for the same reason [TrainingEventRow] carries one: so
 * no template re-derives a colour from a status, which is how the palette came to be
 * duplicated in the first place.
 */
data class TrainingHistoryRow(
    val record: TrainingRecord,
    val swatch: TrainingEventPalette.Swatch
) {
    /** Its calendar session is gone. The page says so, and offers to remove the record. */
    val isOrphaned: Boolean get() = record.isOrphaned
}

/**
 * One month of confirmed training.
 *
 * [attendedMinutes] counts only the sessions actually attended — a month's total is hours
 * trained, and a skipped session contributes none — while [sessionCount] counts every
 * confirmed session, attended and skipped alike, because that is how many rows are shown.
 */
data class TrainingHistoryMonth(
    val label: String,
    val rows: List<TrainingHistoryRow>,
    val attendedMinutes: Long
) {
    val sessionCount: Int get() = rows.size

    val sessionLabel: String get() = if (sessionCount == 1) "1 session" else "$sessionCount sessions"

    val totalLabel: String get() = formatMinutes(attendedMinutes)
}

/** The whole of a user's confirmed training, newest month first. */
data class TrainingHistory(
    val months: List<TrainingHistoryMonth>
) {
    val isEmpty: Boolean get() = months.isEmpty()

    /** Drives the page's explanation of what an orphaned row is; zero hides it. */
    val orphanedCount: Int get() = months.sumOf { month -> month.rows.count { it.isOrphaned } }
}
```

- [ ] **Step 5: Write the service**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryService.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingHistory
import java.util.UUID

/** The signed-in user's confirmed training, and the one correction it allows. */
interface TrainingHistoryService {

    fun historyForCurrentUser(): TrainingHistory

    /**
     * Removes a record whose calendar session no longer exists.
     *
     * Only orphaned records: while the session is still there, the way to correct a mis-mark
     * is to change the session's attendance, which rewrites the record. Deleting the record
     * directly would leave a confirmed session with no history behind it.
     */
    fun deleteOrphanedRecord(recordId: UUID)
}
```

Create `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceImpl.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingHistoryMonth
import com.jankowski.rafal.dancebook.dto.TrainingHistoryRow
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Confirmed training, newest first, grouped by month.
 *
 * Records outlive the sessions they describe, so they need somewhere to be seen: the calendar,
 * the agenda and the timeline all draw `training_event`, and none of them can show a session
 * that has been deleted. This page is where an orphaned record is visible, and the only place
 * one can be corrected.
 *
 * Loads the whole history rather than windowing it, the way the statistics page does: a user's
 * confirmed sessions are few, and grouping needs all of a month at once anyway.
 */
@Service
@Transactional(readOnly = true)
class TrainingHistoryServiceImpl(
    private val trainingRecordRepository: TrainingRecordRepository,
    private val appUserService: AppUserService
) : TrainingHistoryService {

    companion object {
        private val log = LoggerFactory.getLogger(TrainingHistoryServiceImpl::class.java)
        private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    }

    override fun historyForCurrentUser(): TrainingHistory {
        val currentUser = appUserService.getCurrentUser()
        val records = trainingRecordRepository.findAllByCreatedByOrderByOccurredAtDesc(currentUser)
        log.debug("Building training history of {} records for user '{}'", records.size, currentUser.username)

        // groupBy keeps insertion order, and the records arrive newest first, so the months
        // come out newest first without a second sort.
        val months = records.groupBy { YearMonth.from(it.occurredAt) }
            .map { (month, monthRecords) ->
                TrainingHistoryMonth(
                    label = month.format(MONTH_LABEL),
                    rows = monthRecords.map {
                        TrainingHistoryRow(it, TrainingEventPalette.swatchFor(it.outcome))
                    },
                    attendedMinutes = monthRecords
                        .filter { it.outcome == TrainingOutcome.ATTENDED }
                        .sumOf { it.durationMinutes.toLong() }
                )
            }

        return TrainingHistory(months)
    }

    @Transactional
    override fun deleteOrphanedRecord(recordId: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val record = trainingRecordRepository.findById(recordId).orElseThrow {
            EntityNotFoundException("Could not find training record with id $recordId")
        }
        checkOwnership(record, currentUser)
        check(record.isOrphaned) {
            "This session still exists — change its attendance there rather than removing the record"
        }

        log.debug("User '{}' removing orphaned training record '{}'", currentUser.username, record.title)
        trainingRecordRepository.delete(record)
    }

    private fun checkOwnership(record: TrainingRecord, currentUser: AppUser) {
        if (record.createdBy?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to remove this training record")
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.TrainingHistoryServiceTest"`
Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingHistory.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/dto/TrainingEventPalette.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceImpl.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingHistoryServiceTest.kt
git commit -m "Add the training history read model"
```

---

## Task 6: The history page

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryWebController.kt`
- Create: `src/main/resources/templates/training-events/history.html`
- Modify: `src/main/resources/templates/training-events/list.html:12`
- Modify: `src/main/resources/templates/training-events/stats.html:11`
- Modify: `src/main/resources/templates/training-events/timeline.html:14`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryViewRenderingTest.kt`

**Interfaces:**
- Consumes: `TrainingHistoryService.historyForCurrentUser()` and `deleteOrphanedRecord(UUID)` from Task 5.
- Produces: routes `GET /training-events/history` (view `training-events/history`, model attribute `history`) and `POST /training-events/history/{recordId}/delete` (redirects to `/training-events/history`).

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryViewRenderingTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.TrainingEventPalette
import com.jankowski.rafal.dancebook.dto.TrainingHistory
import com.jankowski.rafal.dancebook.dto.TrainingHistoryMonth
import com.jankowski.rafal.dancebook.dto.TrainingHistoryRow
import com.jankowski.rafal.dancebook.model.TrainingEventType
import com.jankowski.rafal.dancebook.model.TrainingOutcome
import com.jankowski.rafal.dancebook.model.TrainingRecord
import com.jankowski.rafal.dancebook.model.TrainingRecordSegment
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingHistoryService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renders the history page for real.
 *
 * TrainingHistoryServiceTest checks the grouping and the removal rules going into the model;
 * neither notices a template that asks the model for something it does not have, or an
 * orphaned row that forgets to say so. These do.
 *
 * Mirrors the TrainingTimelineViewRenderingTest harness: same WebMvcTest slice shape, same
 * NavbarAdvice mocks.
 */
@WebMvcTest(
    controllers = [TrainingHistoryWebController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
        OAuth2ClientWebSecurityAutoConfiguration::class
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [SecurityConfig::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class TrainingHistoryViewRenderingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var trainingHistoryService: TrainingHistoryService

    // Pulled in by NavbarAdvice, which supplies the layout's model on every page.
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService

    private fun record(
        title: String,
        outcome: TrainingOutcome,
        minutes: Int,
        categoryLabel: String? = null,
        orphaned: Boolean = false
    ) = TrainingRecord().apply {
        id = UUID.randomUUID()
        trainingEventId = UUID.randomUUID()
        occurredAt = LocalDateTime.of(2026, 9, 10, 18, 0)
        durationMinutes = minutes
        this.outcome = outcome
        this.title = title
        eventType = TrainingEventType.TRAINING
        if (orphaned) orphanedAt = LocalDateTime.of(2026, 9, 11, 9, 0)
        categoryLabel?.let {
            segments = mutableListOf(
                TrainingRecordSegment().apply {
                    categoryName = it
                    durationMinutes = minutes
                    sortOrder = 0
                }
            )
        }
    }

    private fun row(record: TrainingRecord) =
        TrainingHistoryRow(record, TrainingEventPalette.swatchFor(record.outcome))

    @Test
    fun `renders months, totals, style badges and the palette colour of each outcome`() {
        val attended = record("Monday practice", TrainingOutcome.ATTENDED, 90, categoryLabel = "Standard")
        val skipped = record("Missed lesson", TrainingOutcome.SKIPPED, 60)
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(
            TrainingHistory(
                listOf(
                    TrainingHistoryMonth("September 2026", listOf(row(attended), row(skipped)), attendedMinutes = 90)
                )
            )
        )

        mockMvc.perform(get("/training-events/history"))
            .andExpect(status().isOk)
            .andExpect(view().name("training-events/history"))
            .andExpect(content().string(containsString("September 2026")))
            .andExpect(content().string(containsString("2 sessions")))
            .andExpect(content().string(containsString("1h 30m")))
            .andExpect(content().string(containsString("Monday practice")))
            .andExpect(content().string(containsString("Missed lesson")))
            .andExpect(content().string(containsString("Standard 90min")))
            .andExpect(content().string(containsString(TrainingEventPalette.ATTENDED.color)))
            .andExpect(content().string(containsString(TrainingEventPalette.SKIPPED.color)))
    }

    @Test
    fun `an orphaned record says its session is gone and offers to remove it`() {
        val orphan = record("Deleted session", TrainingOutcome.ATTENDED, 60, orphaned = true)
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(
            TrainingHistory(
                listOf(TrainingHistoryMonth("September 2026", listOf(row(orphan)), attendedMinutes = 60))
            )
        )

        mockMvc.perform(get("/training-events/history"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Session deleted")))
            .andExpect(content().string(containsString("/training-events/history/${orphan.id}/delete")))
    }

    @Test
    fun `an empty history explains itself`() {
        `when`(trainingHistoryService.historyForCurrentUser()).thenReturn(TrainingHistory(emptyList()))

        mockMvc.perform(get("/training-events/history"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No confirmed training yet")))
    }

    @Test
    fun `removing a record goes to the service and returns to the page`() {
        val recordId = UUID.randomUUID()

        mockMvc.perform(post("/training-events/history/$recordId/delete").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/training-events/history"))

        verify(trainingHistoryService).deleteOrphanedRecord(recordId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.TrainingHistoryViewRenderingTest"`
Expected: FAIL — compilation error, `TrainingHistoryWebController` unresolved.

- [ ] **Step 3: Write the controller**

Create `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryWebController.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.TrainingHistoryService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * The training history: confirmed sessions, including the ones whose calendar entry is gone.
 *
 * Its own controller, like the statistics and timeline pages, rather than more weight on
 * TrainingEventWebController. The route sits under /training-events so the whole family of
 * training URLs stays together, and NavbarAdvice.activeNav() therefore already highlights
 * Training for it — no branch of its own, unlike the timeline, which is a top-level nav entry.
 */
@Controller
@RequestMapping("/training-events/history")
class TrainingHistoryWebController(
    private val trainingHistoryService: TrainingHistoryService
) {

    @GetMapping
    fun showHistory(model: Model): String {
        model.addAttribute("pageTitle", "Training history")
        model.addAttribute("history", trainingHistoryService.historyForCurrentUser())
        return "training-events/history"
    }

    /**
     * The one correction this page allows: dropping a record whose session has been deleted.
     * A record that still has its session is corrected by re-marking the session instead, so
     * the service rejects it rather than this controller having to know the rule.
     */
    @PostMapping("/{recordId}/delete")
    fun deleteRecord(@PathVariable recordId: UUID): String {
        trainingHistoryService.deleteOrphanedRecord(recordId)
        return "redirect:/training-events/history"
    }
}
```

- [ ] **Step 4: Write the template**

Create `src/main/resources/templates/training-events/history.html`:

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: html(content=~{::section})}">

<section th:with="activeNav='training-events'" class="space-y-lg">

    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div class="page-header mb-0">
            <h1 class="page-title text-3xl">Training history</h1>
            <p class="page-subtitle">Every session you confirmed — kept even after its calendar entry is gone.</p>
        </div>
        <div class="flex flex-wrap items-center gap-2 shrink-0">
            <a href="/training-events" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">list</span>
                Agenda
            </a>
            <a href="/training-events/timeline" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">timeline</span>
                Timeline
            </a>
            <a href="/training-events/stats" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">monitoring</span>
                Stats
            </a>
        </div>
    </div>

    <div th:if="${history.isEmpty}" class="empty-state">
        <span class="material-symbols-outlined empty-state-icon">history</span>
        <h3 class="empty-state-title">No confirmed training yet</h3>
        <p class="empty-state-text">
            Mark a session attended or skipped and it is recorded here for good — deleting the
            session afterwards will not take it away.
        </p>
        <a href="/training-events" class="btn-primary mt-4">Go to sessions</a>
    </div>

    <!-- Shown only when there is something to explain: an orphaned row is the one row on this
         page that cannot be corrected from the calendar, so it needs a sentence. -->
    <div th:if="${history.orphanedCount > 0}"
         class="card p-4 flex items-start gap-3 font-body-md text-[14px] text-on-surface-variant">
        <span class="material-symbols-outlined text-[20px] text-secondary shrink-0" aria-hidden="true">info</span>
        <p>
            <span th:text="${history.orphanedCount}">1</span>
            of these sessions has been deleted from your calendar. The record stays, so your hours
            and streak stand. If you marked it by mistake, remove the record here.
        </p>
    </div>

    <div th:each="month : ${history.months}" class="space-y-gutter">

        <div class="tc-month-heading">
            <h2 class="font-headline-md text-xl font-semibold text-on-surface" th:text="${month.label}">September 2026</h2>
            <p class="font-body-md text-[14px] text-on-surface-variant tabular-nums">
                <span th:text="${month.sessionLabel}">6 sessions</span>
                <span class="text-outline"> · </span>
                <span th:text="${month.totalLabel}">9h 30m</span> trained
            </p>
        </div>

        <div class="space-y-3">
            <div th:each="row : ${month.rows}" th:with="record=${row.record}"
                 class="card p-4 sm:p-5 flex items-start gap-3 sm:gap-4">

                <!-- Date rail; the stripe and tint are the recorded outcome. -->
                <div class="tc-rail"
                     th:style="'border-left-color:' + ${row.swatch.color} + ';background-color:' + ${row.swatch.tint}">
                    <span class="tc-rail-day" th:text="${#temporals.format(record.occurredAt, 'd')}">10</span>
                    <span class="tc-rail-weekday" th:text="${#temporals.format(record.occurredAt, 'EEE')}">Thu</span>
                </div>

                <div class="flex-1 min-w-0">
                    <!-- A linked record still has a session to open; an orphaned one has nothing
                         to link to, so it is plain text rather than a link that 404s. -->
                    <a th:unless="${row.isOrphaned}"
                       th:href="@{/training-events/{id}(id=${record.trainingEventId})}"
                       class="block truncate font-headline-lg text-base sm:text-lg font-semibold text-on-surface
                              hover:text-primary transition-colors"
                       th:text="${record.title}">Monday practice</a>
                    <p th:if="${row.isOrphaned}"
                       class="block truncate font-headline-lg text-base sm:text-lg font-semibold text-on-surface"
                       th:text="${record.title}">Monday practice</p>

                    <p class="font-body-md text-[14px] sm:text-body-md text-on-surface-variant mt-0.5 tabular-nums">
                        <span th:text="${#temporals.format(record.occurredAt, 'HH:mm')}">18:00</span>
                        <span class="text-outline"> · </span>
                        <span th:text="${record.durationMinutes} + ' min'">120 min</span>
                    </p>

                    <div class="tc-badges mt-2">
                        <span class="badge-primary" th:text="${record.eventType}">TRAINING</span>

                        <span th:each="segment : ${record.segments}" class="badge-secondary"
                              th:text="${segment.label} + ' ' + ${segment.durationMinutes} + 'min'">Standard 60min</span>

                        <span th:classappend="${record.outcome.name() == 'ATTENDED' ? 'badge-success' : 'badge-danger'}"
                              th:text="${row.swatch.label}">Attended</span>

                        <span th:if="${row.isOrphaned}" class="badge-neutral">Session deleted</span>
                    </div>
                </div>

                <!-- Only orphaned records are removable here. While the session exists, the way
                     to correct a mis-mark is to change its attendance, which rewrites this row. -->
                <form th:if="${row.isOrphaned}"
                      th:action="@{/training-events/history/{id}/delete(id=${record.id})}" method="post"
                      class="shrink-0"
                      data-confirm="Remove this training record? Your hours and attendance rate will be recalculated without it.">
                    <button type="submit" class="btn-outline" th:aria-label="'Remove record: ' + ${record.title}">
                        <span class="material-symbols-outlined text-[20px]">delete</span>
                    </button>
                </form>
            </div>
        </div>
    </div>
</section>
</html>
```

- [ ] **Step 5: Link the page from the other three training pages**

In `src/main/resources/templates/training-events/list.html`, `stats.html` and `timeline.html`, the page-header button cluster is a `<div class="flex items-center gap-2 shrink-0">`. In each of the three files:

1. Change that opening tag to `<div class="flex flex-wrap items-center gap-2 shrink-0">` — a fourth button would otherwise overflow on a narrow screen.
2. Insert this anchor as the **first** child of that cluster:

```html
            <a href="/training-events/history" class="btn-outline">
                <span class="material-symbols-outlined text-[20px]">history</span>
                History
            </a>
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.TrainingHistoryViewRenderingTest"`
Expected: PASS, 4 tests.

- [ ] **Step 7: Rebuild Tailwind and verify the other page tests still pass**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — including `buildTailwind`, which must see the new template.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryWebController.kt \
        src/main/resources/templates/training-events/history.html \
        src/main/resources/templates/training-events/list.html \
        src/main/resources/templates/training-events/stats.html \
        src/main/resources/templates/training-events/timeline.html \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingHistoryViewRenderingTest.kt
git commit -m "Add the training history page"
```

---

## Task 7: Full verification

**Files:** none changed unless a failure turns one up.

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, no failing tests.

- [ ] **Step 2: Verify the migration applies to a real database**

```bash
docker compose up -d postgres
./gradlew bootRun
```

Expected: the app boots. `ddl-auto=validate` passing is the check that the V28 columns match the two new entities exactly. Stop it with Ctrl-C once it has started.

If it fails to boot for want of environment variables rather than schema mismatch, that is a local configuration problem and not a plan failure — note it and move on with `./gradlew build` as the gate.

- [ ] **Step 3: Walk the acceptance criteria against the implementation**

Confirm each of these, and say which test covers it:

- Marking a session Attended or Skipped writes a record; un-marking removes it → `TrainingRecordWriterTest`
- Editing a session updates its record while the session exists → `TrainingRecordWriterTest`
- Deleting a session singly, in bulk or across a series leaves the record and marks it orphaned → `TrainingEventPersistenceTest`, `TrainingSeriesPersistenceTest`
- An orphaned record keeps a readable title, duration and breakdown → `TrainingRecordTest`, `TrainingHistoryViewRenderingTest`
- Deleting a category does not destroy records → `ON DELETE SET NULL` in V28, `TrainingStatsServiceTest`
- Deleting a user does not destroy records → no `ON DELETE` clause on `created_by_id` in V28
- History figures from records, schedule figures from events → `TrainingStatsServiceTest`
- Breakdown survives a rename → `TrainingStatsServiceTest`
- Reconciliation invariant holds → `TrainingStatsServiceTest`
- History view by month, marks orphans, allows removal → `TrainingHistoryServiceTest`, `TrainingHistoryViewRenderingTest`
- Existing confirmed sessions backfilled → V28
- Record writes commit with the session write → `TrainingEventPersistenceTest`

- [ ] **Step 4: Commit anything the verification turned up, then open the PR**

```bash
git push -u origin HEAD
gh pr create --title "Record training history separately from calendar events" --body "$(cat <<'EOF'
Closes #49.

Statistics read `training_event` rows, which are also the Google-mirrored calendar schedule,
so tidying the calendar rewrote the past: delete a skipped session and your attendance rate
improved. Confirmed training now lives in its own `training_record` table, written in the same
transaction as the session and holding it by plain id with no foreign key — the `activity_event`
convention — so a record survives its session's deletion as an orphaned row.

- `V28__add_training_record.sql` adds both tables and backfills every already-confirmed session
- `TrainingRecordWriter`, called from the transactional persistence beans, keeps records in step
- Statistics are deliberately hybrid: history from records, schedule counts from events
- The style breakdown now groups by category id, so a rename no longer relabels history
- A new `/training-events/history` page shows records by month and removes orphaned mis-marks

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review Notes

Checked against the spec:

- **"A record survives the deletion of its session"** — Task 1 (no FK), Task 3 (orphan on delete).
- **"Records follow edits while linked, then freeze"** — Task 2, `sync` refuses to touch an orphaned record.
- **"One record per session, updated in place"** — Task 1's `UNIQUE` on `training_event_id`, Task 2's update-in-place.
- **"Records are written in the same transaction as the session"** — Task 3.
- **"Statistics become deliberately hybrid"** — Task 4.
- **"The record's owner reference must not cascade"** — Task 1's V28, restated in Global Constraints.
- **"Style breakdowns group by category id and label from the live category"** — Task 4's `CategoryKey`.
- **"Streak becomes exactly what it claims to be"** — Task 4's `currentStreak`.
- **"Session duration ... cannot drift"** — Task 1's recorded `duration_minutes`.
- **"History view"** — Tasks 5 and 6.
- **"Existing data ... backfilled"** — Task 1's V28.
- **"Both series bulk methods ... need explicit record handling"** — Task 3.
- **"The session-edit form carries attendanceStatus, so a plain edit is an attendance-change path too"** — covered because `applyUpdate` syncs, and `applyUpdate` is what both the edit form and the one-tap confirm end up calling.

Deliberate scope decisions, flagged rather than silently taken:

- **The history page loads full history rather than windowing it.** The acceptance criteria ask for month grouping, orphan marking and removal, not paging; the statistics page already loads a user's whole history for the same reason. If history grows large enough to matter, the timeline's two-step windowed fetch is the pattern to copy.
- **Only orphaned records are removable from the history page.** Removing a record whose session still exists would leave a confirmed session with no history and nothing to recreate it; the correction for that case is re-marking the session, which rewrites the record.
