package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant

@Testcontainers
class PersistentLoginsMigrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun migrateTo(version: String? = null) {
        val config = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
        if (version != null) {
            config.target(MigrationVersion.fromVersion(version))
        }
        config.load().migrate()
    }

    @Test
    fun `V50 creates persistent_logins table supporting token persistence`() {
        migrateTo("49")

        connect().use { db ->
            val rs = db.metaData.getTables(null, null, "persistent_logins", null)
            assertFalse(rs.next(), "persistent_logins should not exist before V50")
        }

        migrateTo("50")

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        connect().use { db ->
            val rs = db.metaData.getTables(null, null, "persistent_logins", null)
            assertTrue(rs.next(), "persistent_logins should exist after V50")

            db.prepareStatement(
                """
                INSERT INTO persistent_logins (series, username, token, previous_token, last_used, remember_me, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, "series-123")
                ps.setString(2, "dancer@example.com")
                ps.setString(3, "token-hash-456")
                ps.setString(4, null)
                ps.setTimestamp(5, Timestamp.from(now))
                ps.setBoolean(6, true)
                ps.setTimestamp(7, Timestamp.from(expiresAt))
                assertEquals(1, ps.executeUpdate())
            }

            db.prepareStatement("SELECT * FROM persistent_logins WHERE series = ?").use { ps ->
                ps.setString(1, "series-123")
                ps.executeQuery().use { queryRs ->
                    assertTrue(queryRs.next())
                    assertEquals("dancer@example.com", queryRs.getString("username"))
                    assertEquals("token-hash-456", queryRs.getString("token"))
                    assertNull(queryRs.getString("previous_token"))
                    assertTrue(queryRs.getBoolean("remember_me"))
                }
            }

            // Update with previous_token
            db.prepareStatement(
                "UPDATE persistent_logins SET token = ?, previous_token = ? WHERE series = ?"
            ).use { ps ->
                ps.setString(1, "token-hash-789")
                ps.setString(2, "token-hash-456")
                ps.setString(3, "series-123")
                assertEquals(1, ps.executeUpdate())
            }

            db.prepareStatement("SELECT token, previous_token FROM persistent_logins WHERE series = ?").use { ps ->
                ps.setString(1, "series-123")
                ps.executeQuery().use { queryRs ->
                    assertTrue(queryRs.next())
                    assertEquals("token-hash-789", queryRs.getString("token"))
                    assertEquals("token-hash-456", queryRs.getString("previous_token"))
                }
            }
        }
    }
}
