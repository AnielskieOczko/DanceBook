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
class MedalLevelMigrationTest {

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
    fun `V41 adds a nullable medal_level that leaves existing figures with none`() {
        flyway("40").migrate()

        val typeId = UUID.randomUUID()
        val figureId = UUID.randomUUID()
        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate("INSERT INTO dance_type (id, name, predefined, category_id) SELECT '$typeId'::uuid, 'Medal Waltz', false, id FROM dance_category LIMIT 1")
                st.executeUpdate(
                    "INSERT INTO dance_figure (id, name, dance_type_id, dance_class, predefined) VALUES ('$figureId', 'Old Figure', '$typeId', 'D', true)"
                )
            }
        }

        flyway().migrate()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT medal_level FROM dance_figure WHERE id = '$figureId'").use { rs ->
                    rs.next()
                    assertNull(rs.getString("medal_level"))
                }
                st.executeUpdate("UPDATE dance_figure SET medal_level = 'GOLD' WHERE id = '$figureId'")
                st.executeQuery("SELECT medal_level, dance_class FROM dance_figure WHERE id = '$figureId'").use { rs ->
                    rs.next()
                    assertEquals("GOLD", rs.getString("medal_level"))
                    assertEquals("D", rs.getString("dance_class"))
                }
            }
        }
    }
}
