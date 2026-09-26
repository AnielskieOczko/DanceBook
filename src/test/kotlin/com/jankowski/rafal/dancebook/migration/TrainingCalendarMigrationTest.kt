package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers
class TrainingCalendarMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target))
        }
        return config.load()
    }

    @Test
    fun `V29 adds training_calendar and nullable calendar_id, enforcing unique default and restrict delete`() {
        // Migrate to V28
        flyway("28").migrate()

        val userId = UUID.randomUUID()
        val eventId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES ('$userId', 'v29-tester', 'V29 Tester', 'x', 'USER', NOW())
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO training_event
                        (id, title, start_time, end_time, event_type, attendance_status, created_by_id, created_at, updated_at)
                    VALUES ('$eventId', 'Pre-V29 Event', TIMESTAMP '2026-03-02 18:00:00',
                            TIMESTAMP '2026-03-02 19:00:00', 'TRAINING', 'PLANNED', '$userId', NOW(), NOW())
                    """.trimIndent()
                )
            }
        }

        // Migrate to V29
        flyway("29").migrate()

        val cal1Id = UUID.randomUUID()
        val cal2Id = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                // Assert calendar_id exists and is null for pre-V29 event
                st.executeQuery("SELECT calendar_id FROM training_event WHERE id = '$eventId'").use {
                    assertTrue(it.next())
                    assertNull(it.getObject("calendar_id"), "calendar_id exists and is null on existing rows")
                }

                // Insert a default calendar
                st.executeUpdate(
                    """
                    INSERT INTO training_calendar (id, google_calendar_id, display_name, is_default, enabled, created_at, updated_at)
                    VALUES ('$cal1Id', 'cal1@group.calendar.google.com', 'Calendar 1', true, true, NOW(), NOW())
                    """.trimIndent()
                )

                // A second is_default = true insert raises unique violation
                assertThrows(SQLException::class.java) {
                    st.executeUpdate(
                        """
                        INSERT INTO training_calendar (id, google_calendar_id, display_name, is_default, enabled, created_at, updated_at)
                        VALUES ('$cal2Id', 'cal2@group.calendar.google.com', 'Calendar 2', true, true, NOW(), NOW())
                        """.trimIndent()
                    )
                }

                // Linking the event to cal1 and attempting to delete cal1 raises a foreign key violation (ON DELETE RESTRICT)
                st.executeUpdate("UPDATE training_event SET calendar_id = '$cal1Id' WHERE id = '$eventId'")
                assertThrows(SQLException::class.java) {
                    st.executeUpdate("DELETE FROM training_calendar WHERE id = '$cal1Id'")
                }
            }
        }
    }
}
