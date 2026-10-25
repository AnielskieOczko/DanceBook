package com.jankowski.rafal.dancebook.config

import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key="])
class DatabaseConnectionPoolPropertiesTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired
    private lateinit var dataSource: DataSource

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    @Test
    fun `connection pool is Hikari and configured shorter than Neon 5-minute idle disconnect`() {
        assertInstanceOf(HikariDataSource::class.java, dataSource)
        val hikari = dataSource as HikariDataSource

        val neonIdleTimeoutMs = 5 * 60 * 1000L

        // maxLifetime must be strictly shorter than Neon's 5-minute idle disconnect
        assertEquals(240000L, hikari.maxLifetime, "maxLifetime should be 4 minutes (240,000 ms)")
        assertTrue(hikari.maxLifetime < neonIdleTimeoutMs, "maxLifetime must be shorter than Neon's idle disconnect")

        // idleTimeout must be shorter than maxLifetime and Neon's idle disconnect
        assertEquals(120000L, hikari.idleTimeout, "idleTimeout should be 2 minutes (120,000 ms)")
        assertTrue(hikari.idleTimeout < neonIdleTimeoutMs, "idleTimeout must be shorter than Neon's idle disconnect")
        assertTrue(hikari.idleTimeout < hikari.maxLifetime, "idleTimeout must be shorter than maxLifetime")

        // minimumIdle must be 0 so the pool can completely drain when idle, avoiding dead connections
        assertEquals(0, hikari.minimumIdle, "minimumIdle should be 0 to allow pool to drain when inactive")

        // connectionTimeout should allow ample time for Neon cold start resume
        assertEquals(30000L, hikari.connectionTimeout, "connectionTimeout should be 30 seconds")
    }
}
