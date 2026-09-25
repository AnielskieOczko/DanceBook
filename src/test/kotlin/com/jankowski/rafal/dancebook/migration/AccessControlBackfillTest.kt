package com.jankowski.rafal.dancebook.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

@Testcontainers
class AccessControlBackfillTest {

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
    fun `V31 backfills material owner from activity feed, converts is_public to visibility, and creates share table`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("30"))
            .load()
            .migrate()

        val adminUserId = UUID.randomUUID()
        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()

        val matWithEventId = UUID.randomUUID()
        val matWithoutEventId = UUID.randomUUID()

        val publicListId = UUID.randomUUID()
        val privateListId = UUID.randomUUID()

        val publicChoreoId = UUID.randomUUID()
        val privateChoreoId = UUID.randomUUID()

        connect().use { db ->
            db.createStatement().use { st ->
                // Insert users
                st.executeUpdate(
                    """
                    INSERT INTO app_user (id, username, display_name, password, role, created_at)
                    VALUES 
                      ('$adminUserId', 'admin-user', 'Admin User', 'x', 'ADMIN', NOW() - INTERVAL '10 days'),
                      ('$userAId', 'user-a', 'User A', 'x', 'USER', NOW() - INTERVAL '5 days'),
                      ('$userBId', 'user-b', 'User B', 'x', 'USER', NOW() - INTERVAL '4 days')
                    """.trimIndent()
                )

                // Insert materials
                st.executeUpdate(
                    """
                    INSERT INTO material (id, name, created_at, version)
                    VALUES 
                      ('$matWithEventId', 'Note With Event', NOW() - INTERVAL '3 days', 0),
                      ('$matWithoutEventId', 'Note Without Event', NOW() - INTERVAL '2 days', 0)
                    """.trimIndent()
                )

                // Insert activity event for matWithEventId (actor is User A)
                st.executeUpdate(
                    """
                    INSERT INTO activity_event (id, event_type, actor_id, target_type, target_id, target_name, created_at)
                    VALUES 
                      ('${UUID.randomUUID()}', 'MATERIAL_CREATED', '$userAId', 'MATERIAL', '$matWithEventId', 'Note With Event', NOW() - INTERVAL '3 days')
                    """.trimIndent()
                )

                // Insert custom lists (one public, one private)
                st.executeUpdate(
                    """
                    INSERT INTO custom_list (id, name, owner_id, is_public, created_at)
                    VALUES 
                      ('$publicListId', 'Public Collection', '$userAId', true, NOW()),
                      ('$privateListId', 'Private Collection', '$userAId', false, NOW())
                    """.trimIndent()
                )

                // Insert dance_type needed for choreography
                val danceTypeId = UUID.randomUUID()
                val categoryId = UUID.randomUUID()
                st.executeUpdate("INSERT INTO dance_category (id, name, predefined) VALUES ('$categoryId', 'Migration Cat', false)")
                st.executeUpdate("INSERT INTO dance_type (id, name, predefined, category_id) VALUES ('$danceTypeId', 'Migration Type', false, '$categoryId')")

                // Insert choreographies (one public, one private)
                st.executeUpdate(
                    """
                    INSERT INTO choreography (id, name, dance_type_id, owner_id, is_public, created_at, updated_at)
                    VALUES 
                      ('$publicChoreoId', 'Public Choreo', '$danceTypeId', '$userAId', true, NOW(), NOW()),
                      ('$privateChoreoId', 'Private Choreo', '$danceTypeId', '$userAId', false, NOW(), NOW())
                    """.trimIndent()
                )
            }
        }

        // Run V31 migration
        flyway().migrate()

        connect().use { db ->
            db.createStatement().use { st ->
                // Verify material backfill
                st.executeQuery("SELECT owner_id, visibility FROM material WHERE id = '$matWithEventId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(userAId.toString(), rs.getString("owner_id"), "owner backfilled from MATERIAL_CREATED event")
                    assertEquals("PUBLIC", rs.getString("visibility"), "existing material is PUBLIC")
                }

                st.executeQuery("SELECT owner_id, visibility FROM material WHERE id = '$matWithoutEventId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(adminUserId.toString(), rs.getString("owner_id"), "owner fallback to admin")
                    assertEquals("PUBLIC", rs.getString("visibility"), "existing material is PUBLIC")
                }

                // Verify custom_list visibility conversion
                st.executeQuery("SELECT visibility FROM custom_list WHERE id = '$publicListId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("PUBLIC", rs.getString("visibility"))
                }
                st.executeQuery("SELECT visibility FROM custom_list WHERE id = '$privateListId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("PRIVATE", rs.getString("visibility"))
                }

                // Verify choreography visibility conversion
                st.executeQuery("SELECT visibility FROM choreography WHERE id = '$publicChoreoId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("PUBLIC", rs.getString("visibility"))
                }
                st.executeQuery("SELECT visibility FROM choreography WHERE id = '$privateChoreoId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("PRIVATE", rs.getString("visibility"))
                }

                // Verify activity_event target_visibility
                st.executeQuery("SELECT target_visibility FROM activity_event WHERE target_id = '$matWithEventId'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("PUBLIC", rs.getString("target_visibility"))
                }

                // Verify share table exists and is empty
                st.executeQuery("SELECT COUNT(*) FROM share").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }
}
