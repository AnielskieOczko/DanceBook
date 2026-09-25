package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers
class TrainingAttendanceMigrationTest {

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
    fun `V34 creates attendance table, backfills from events, and enforces unique record per event and user`() {
        migrateTo("33")

        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()
        val calendarId = UUID.randomUUID()
        val event1Id = UUID.randomUUID()
        val event2Id = UUID.randomUUID()
        val event3Id = UUID.randomUUID()
        val event4Id = UUID.randomUUID()
        val record1Id = UUID.randomUUID()
        val record3Id = UUID.randomUUID()
        val orphanRecordId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES ('$userAId', 'user-a', 'User A', 'x', 'USER', NOW()),
                           ('$userBId', 'user-b', 'User B', 'x', 'USER', NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_calendar
                        (id, google_calendar_id, display_name, is_default, enabled, created_at, updated_at)
                    VALUES ('$calendarId', 'test-cal@group.calendar.google.com', 'Club Calendar', true, true, NOW(), NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_event
                        (id, title, start_time, end_time, event_type, attendance_status,
                         calendar_id, created_by_id, created_at, updated_at)
                    VALUES
                        ('$event1Id', 'Latin practice', TIMESTAMP '2026-03-01 18:00:00', TIMESTAMP '2026-03-01 19:00:00', 'TRAINING', 'ATTENDED', '$calendarId', '$userAId', NOW(), NOW()),
                        ('$event2Id', 'Standard practice', TIMESTAMP '2026-03-02 18:00:00', TIMESTAMP '2026-03-02 19:00:00', 'TRAINING', 'PLANNED', '$calendarId', '$userAId', NOW(), NOW()),
                        ('$event3Id', 'Solo drills', TIMESTAMP '2026-03-03 18:00:00', TIMESTAMP '2026-03-03 19:00:00', 'TRAINING', 'SKIPPED', '$calendarId', '$userBId', NOW(), NOW()),
                        ('$event4Id', 'Cancelled workshop', TIMESTAMP '2026-03-04 18:00:00', TIMESTAMP '2026-03-04 19:00:00', 'WORKSHOP', 'CANCELLED', '$calendarId', '$userAId', NOW(), NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_record
                        (id, training_event_id, occurred_at, duration_minutes, outcome, title,
                         event_type, created_by_id, calendar_id, calendar_name, created_at, updated_at)
                    VALUES
                        ('$record1Id', '$event1Id', TIMESTAMP '2026-03-01 18:00:00', 60, 'ATTENDED', 'Latin practice', 'TRAINING', '$userAId', '$calendarId', 'Club Calendar', NOW(), NOW()),
                        ('$record3Id', '$event3Id', TIMESTAMP '2026-03-03 18:00:00', 60, 'SKIPPED', 'Solo drills', 'TRAINING', '$userBId', '$calendarId', 'Club Calendar', NOW(), NOW()),
                        ('$orphanRecordId', '${UUID.randomUUID()}', TIMESTAMP '2026-02-01 18:00:00', 90, 'ATTENDED', 'Deleted session', 'TRAINING', '$userAId', '$calendarId', 'Club Calendar', NOW(), NOW())
                    """.trimIndent()
                )
            }
        }

        migrateTo("34")

        connect().use { db ->
            db.createStatement().use { st ->
                // 1. Verify attendance table rows
                st.executeQuery(
                    "SELECT training_event_id, user_id, status FROM attendance ORDER BY training_event_id"
                ).use { rs ->
                    val rows = mutableMapOf<UUID, Pair<UUID, String>>()
                    while (rs.next()) {
                        val eventId = UUID.fromString(rs.getString("training_event_id"))
                        val userId = UUID.fromString(rs.getString("user_id"))
                        val status = rs.getString("status")
                        rows[eventId] = userId to status
                    }
                    assertEquals(4, rows.size)
                    assertEquals(userAId to "ATTENDED", rows[event1Id])
                    assertEquals(userAId to "PLANNED", rows[event2Id])
                    assertEquals(userBId to "SKIPPED", rows[event3Id])
                    assertEquals(userAId to "CANCELLED", rows[event4Id])
                }

                // 2. Verify attendance_status column was dropped from training_event
                assertThrows(SQLException::class.java) {
                    st.executeQuery("SELECT attendance_status FROM training_event")
                }

                // 3. Verify unique constraint allows userB to have a record on event1
                val recordUserBOnEvent1 = UUID.randomUUID()
                st.executeUpdate(
                    """
                    INSERT INTO training_record
                        (id, training_event_id, occurred_at, duration_minutes, outcome, title,
                         event_type, created_by_id, calendar_id, calendar_name, created_at, updated_at)
                    VALUES
                        ('$recordUserBOnEvent1', '$event1Id', TIMESTAMP '2026-03-01 18:00:00', 60, 'SKIPPED', 'Latin practice', 'TRAINING', '$userBId', '$calendarId', 'Club Calendar', NOW(), NOW())
                    """.trimIndent()
                )

                // 4. Verify duplicate record for userA on event1 is rejected
                assertThrows(SQLException::class.java) {
                    st.executeUpdate(
                        """
                        INSERT INTO training_record
                            (id, training_event_id, occurred_at, duration_minutes, outcome, title,
                             event_type, created_by_id, calendar_id, calendar_name, created_at, updated_at)
                        VALUES
                            ('${UUID.randomUUID()}', '$event1Id', TIMESTAMP '2026-03-01 18:00:00', 60, 'ATTENDED', 'Latin practice', 'TRAINING', '$userAId', '$calendarId', 'Club Calendar', NOW(), NOW())
                        """.trimIndent()
                    )
                }
            }
        }
    }
}
