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
import java.sql.Statement
import java.util.UUID

/**
 * V28's backfill, against data that was already there.
 *
 * This cannot be a @SpringBootTest: Flyway runs on boot, so a Spring test would apply V28 to an
 * empty `training_event` table and prove nothing. Here the migration is driven in two steps --
 * up to V27, seed, then V28 -- which is the only arrangement in which the backfill has anything
 * to backfill. It needs no Spring context, and therefore none of the app's required environment
 * variables.
 *
 * Worth its own test because the failure is silent: a wrong join column inserts zero rows rather
 * than raising, and this migration gets exactly one run against the user's real training history.
 */
@Testcontainers
class TrainingRecordBackfillTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway() = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .load()

    @Test
    fun `V28 backfills confirmed sessions and their style breakdown, and leaves the rest alone`() {
        // Everything the app had before this feature existed.
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("27"))
            .load()
            .migrate()

        val userId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val attendedId = UUID.randomUUID()
        val skippedId = UUID.randomUUID()
        val plannedId = UUID.randomUUID()
        val cancelledId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES ('$userId', 'backfill-tester', 'Backfill Tester', 'x', 'USER', NOW())
                    """.trimIndent()
                )
                // "Standard" collides with the predefined category V2 already seeds, whose
                // name-uniqueness constraint this insert would otherwise violate -- so this
                // test's category gets a name of its own.
                st.executeUpdate(
                    "INSERT INTO dance_category (id, name, predefined) VALUES ('$categoryId', 'Backfill Test Style', false)"
                )

                // 90 minutes, attended, with a 60-minute segment in that style.
                insertEvent(st, attendedId, userId, "Attended session", "ATTENDED", "TRAINING", 90)
                st.executeUpdate(
                    """
                    INSERT INTO training_event_segment
                        (id, training_event_id, dance_category_id, duration_minutes, sort_order)
                    VALUES ('${UUID.randomUUID()}', '$attendedId', '$categoryId', 60, 0)
                    """.trimIndent()
                )

                insertEvent(st, skippedId, userId, "Skipped session", "SKIPPED", "WORKSHOP", 60)
                // Neither of these is a confirmed outcome, so neither is history.
                insertEvent(st, plannedId, userId, "Planned session", "PLANNED", "TRAINING", 60)
                insertEvent(st, cancelledId, userId, "Cancelled session", "CANCELLED", "TRAINING", 60)
            }
        }

        flyway().migrate()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM training_record").use {
                    it.next()
                    assertEquals(2, it.getInt(1), "only the attended and skipped sessions are history")
                }

                st.executeQuery(
                    """
                    SELECT outcome, title, duration_minutes, event_type, created_by_id, orphaned_at
                    FROM training_record WHERE training_event_id = '$attendedId'
                    """.trimIndent()
                ).use {
                    assertTrue(it.next(), "the attended session was backfilled")
                    assertEquals("ATTENDED", it.getString("outcome"))
                    assertEquals("Attended session", it.getString("title"))
                    assertEquals(90, it.getInt("duration_minutes"))
                    assertEquals("TRAINING", it.getString("event_type"))
                    assertEquals(userId.toString(), it.getString("created_by_id"))
                    assertNull(it.getTimestamp("orphaned_at"), "a backfilled record is not orphaned")
                }

                st.executeQuery(
                    """
                    SELECT s.category_name, s.duration_minutes, s.sort_order, s.dance_category_id
                    FROM training_record_segment s
                    JOIN training_record r ON r.id = s.training_record_id
                    WHERE r.training_event_id = '$attendedId'
                    """.trimIndent()
                ).use {
                    assertTrue(it.next(), "the style breakdown was backfilled too")
                    assertEquals("Backfill Test Style", it.getString("category_name"))
                    assertEquals(60, it.getInt("duration_minutes"))
                    assertEquals(0, it.getInt("sort_order"))
                    assertEquals(categoryId.toString(), it.getString("dance_category_id"))
                    assertTrue(!it.next(), "exactly one segment")
                }

                st.executeQuery(
                    "SELECT outcome FROM training_record WHERE training_event_id = '$skippedId'"
                ).use {
                    assertTrue(it.next())
                    assertEquals("SKIPPED", it.getString("outcome"))
                }
            }
        }
    }

    private fun insertEvent(
        st: Statement,
        id: UUID,
        userId: UUID,
        title: String,
        status: String,
        type: String,
        minutes: Int
    ) {
        st.executeUpdate(
            """
            INSERT INTO training_event
                (id, title, start_time, end_time, event_type, attendance_status, created_by_id, created_at, updated_at)
            VALUES ('$id', '$title', TIMESTAMP '2026-03-02 18:00:00',
                    TIMESTAMP '2026-03-02 18:00:00' + INTERVAL '$minutes minutes',
                    '$type', '$status', '$userId', NOW(), NOW())
            """.trimIndent()
        )
    }
}
