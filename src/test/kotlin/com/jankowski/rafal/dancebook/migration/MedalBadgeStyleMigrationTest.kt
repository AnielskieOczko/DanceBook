package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@Testcontainers
class MedalBadgeStyleMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
        if (target != null) config.target(MigrationVersion.fromVersion(target))
        return config.load()
    }

    @Test
    fun `V43 adds a nullable medal_badge_style that leaves existing users with null`() {
        flyway("42").migrate()

        val userId = UUID.randomUUID()
        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO app_user (id, username, display_name, role) VALUES ('$userId', 'testuser', 'Test User', 'USER')"
                )
            }
        }

        flyway().migrate()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT medal_badge_style FROM app_user WHERE id = '$userId'").use { rs ->
                    rs.next()
                    assertNull(rs.getString("medal_badge_style"))
                }
                st.executeUpdate("UPDATE app_user SET medal_badge_style = 'MEDAL_SHAPES' WHERE id = '$userId'")
                st.executeQuery("SELECT medal_badge_style FROM app_user WHERE id = '$userId'").use { rs ->
                    rs.next()
                    assertEquals("MEDAL_SHAPES", rs.getString("medal_badge_style"))
                }
            }
        }
    }
}
