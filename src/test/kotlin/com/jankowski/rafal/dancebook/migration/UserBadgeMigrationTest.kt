package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@Testcontainers
class UserBadgeMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
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
    fun `V47 creates user_badge table and enforces uniqueness per user badge_type and month_key`() {
        migrateTo("46")

        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES ('$userAId', 'badge-user-a', 'Badge User A', 'x', 'USER', NOW()),
                           ('$userBId', 'badge-user-b', 'Badge User B', 'x', 'USER', NOW())
                    """.trimIndent()
                )
            }
        }

        migrateTo("47")

        connect().use { db ->
            db.createStatement().use { st ->
                val eventId = UUID.randomUUID()
                st.executeUpdate(
                    """
                    INSERT INTO user_badge (id, user_id, badge_type, month_key, training_event_id, earned_at)
                    VALUES ('${UUID.randomUUID()}', '$userAId', 'STREAK_5', '', '$eventId', NOW()),
                           ('${UUID.randomUUID()}', '$userAId', 'PERFECT_MONTH', '2026-09', '$eventId', NOW()),
                           ('${UUID.randomUUID()}', '$userAId', 'PERFECT_MONTH', '2026-10', NULL, NOW()),
                           ('${UUID.randomUUID()}', '$userBId', 'STREAK_5', '', NULL, NOW())
                    """.trimIndent()
                )

                st.executeQuery("SELECT COUNT(*) FROM user_badge WHERE user_id = '$userAId'").use { rs ->
                    rs.next()
                    assertEquals(3, rs.getInt(1))
                }

                // Duplicate STREAK_5 for user A is rejected
                assertThrows(SQLException::class.java) {
                    st.executeUpdate(
                        """
                        INSERT INTO user_badge (id, user_id, badge_type, month_key, earned_at)
                        VALUES ('${UUID.randomUUID()}', '$userAId', 'STREAK_5', '', NOW())
                        """.trimIndent()
                    )
                }

                // Duplicate PERFECT_MONTH for the same month_key is rejected
                assertThrows(SQLException::class.java) {
                    st.executeUpdate(
                        """
                        INSERT INTO user_badge (id, user_id, badge_type, month_key, earned_at)
                        VALUES ('${UUID.randomUUID()}', '$userAId', 'PERFECT_MONTH', '2026-09', NOW())
                        """.trimIndent()
                    )
                }
            }
        }
    }
}
