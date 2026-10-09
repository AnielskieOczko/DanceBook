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
class ChoreographyMedalLevelMigrationTest {

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
    fun `V42 adds a nullable medal_level that leaves existing choreographies with none`() {
        flyway("41").migrate()

        val userId = UUID.randomUUID()
        val typeId = UUID.randomUUID()
        val choreographyId = UUID.randomUUID()
        connect().use { db ->
            db.createStatement().use { st ->
                st.executeUpdate("INSERT INTO app_user (id, username, display_name, password, role, created_at) VALUES ('$userId', 'choreo-tester', 'Choreo Tester', 'x', 'USER', NOW())")
                st.executeUpdate("INSERT INTO dance_type (id, name, predefined, category_id) SELECT '$typeId'::uuid, 'Choreo Waltz', false, id FROM dance_category LIMIT 1")
                st.executeUpdate(
                    "INSERT INTO choreography (id, name, dance_type_id, owner_id, visibility, created_at, updated_at) VALUES ('$choreographyId', 'Old Choreo', '$typeId', '$userId', 'PRIVATE', NOW(), NOW())"
                )
            }
        }

        flyway().migrate()

        connect().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT medal_level FROM choreography WHERE id = '$choreographyId'").use { rs ->
                    rs.next()
                    assertNull(rs.getString("medal_level"))
                }
                st.executeUpdate("UPDATE choreography SET medal_level = 'GOLD' WHERE id = '$choreographyId'")
                st.executeQuery("SELECT medal_level FROM choreography WHERE id = '$choreographyId'").use { rs ->
                    rs.next()
                    assertEquals("GOLD", rs.getString("medal_level"))
                }
            }
        }
    }
}
