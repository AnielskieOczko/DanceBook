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
