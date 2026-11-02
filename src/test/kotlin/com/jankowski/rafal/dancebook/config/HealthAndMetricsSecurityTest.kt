package com.jankowski.rafal.dancebook.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class HealthAndMetricsSecurityTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    private val objectMapper = ObjectMapper()

    private lateinit var adminUser: AppUser
    private lateinit var regularUser: AppUser

    @BeforeEach
    fun setUp() {
        adminUser = appUserRepository.findByUsername("metrics_admin_tester") ?: appUserRepository.save(AppUser().apply {
            username = "metrics_admin_tester"
            displayName = "Metrics Admin Tester"
            role = Role.ADMIN
        })

        regularUser = appUserRepository.findByUsername("metrics_regular_tester") ?: appUserRepository.save(AppUser().apply {
            username = "metrics_regular_tester"
            displayName = "Metrics Regular Tester"
            role = Role.USER
        })
    }

    @Test
    fun `health endpoint is publicly reachable for Cloud Run probes and exposes only status`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(content().string(not(containsString("diskSpace"))))
            .andExpect(content().string(not(containsString("components"))))
            .andExpect(content().string(not(containsString("db"))))
    }

    @Test
    fun `metrics and sensitive endpoints reject anonymous visitor with redirect or unauthorized`() {
        // Admin metrics endpoint
        mockMvc.perform(get("/admin/metrics"))
            .andExpect { result ->
                val status = result.response.status
                assertTrue(
                    status == 302 || status == 401,
                    "Expected 302 redirect or 401 unauthorized for anonymous visitor, but got $status"
                )
            }

        // Actuator metrics endpoint
        mockMvc.perform(get("/actuator/metrics"))
            .andExpect { result ->
                val status = result.response.status
                assertTrue(
                    status == 302 || status == 401,
                    "Expected 302 redirect or 401 unauthorized for anonymous visitor, but got $status"
                )
            }

        // Actuator env endpoint
        mockMvc.perform(get("/actuator/env"))
            .andExpect { result ->
                val status = result.response.status
                assertTrue(
                    status == 302 || status == 401,
                    "Expected 302 redirect or 401 unauthorized for anonymous visitor, but got $status"
                )
            }

        // Actuator configprops endpoint
        mockMvc.perform(get("/actuator/configprops"))
            .andExpect { result ->
                val status = result.response.status
                assertTrue(
                    status == 302 || status == 401,
                    "Expected 302 redirect or 401 unauthorized for anonymous visitor, but got $status"
                )
            }
    }

    @Test
    fun `metrics and sensitive endpoints reject ordinary authenticated user with 403 Forbidden`() {
        mockMvc.perform(get("/admin/metrics").with(user(regularUser.username).roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/actuator/metrics").with(user(regularUser.username).roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/actuator/env").with(user(regularUser.username).roles("USER")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/actuator/configprops").with(user(regularUser.username).roles("USER")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin metrics endpoint returns memory gc threads and pool metrics to admin`() {
        val result = mockMvc.perform(get("/admin/metrics").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()

        val json = objectMapper.readTree(result.response.contentAsString)

        // Memory metrics
        assertNotNull(json["memory"])
        assertNotNull(json["memory"]["heap"]["usedBytes"])
        assertNotNull(json["memory"]["heap"]["usedMb"])
        assertNotNull(json["memory"]["heap"]["maxMb"])
        assertNotNull(json["memory"]["nonHeap"]["usedBytes"])

        // Garbage collection metrics
        assertNotNull(json["garbageCollection"])
        assertTrue(json["garbageCollection"].isArray)

        // Thread metrics
        assertNotNull(json["threads"])
        assertTrue(json["threads"]["live"].asInt() > 0)
        assertTrue(json["threads"]["peak"].asInt() > 0)

        // Database pool metrics
        assertNotNull(json["databasePool"])
        assertNotNull(json["databasePool"]["totalConnections"])
        assertNotNull(json["databasePool"]["maxPoolSize"])
    }

    @Test
    fun `actuator metrics endpoint is accessible to admin`() {
        mockMvc.perform(get("/actuator/metrics").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.names").isArray)
    }

    @Test
    fun `environment variables and configuration properties endpoints are never exposed`() {
        // Disabled endpoints should return 404 even for admin
        mockMvc.perform(get("/actuator/env").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isNotFound)

        mockMvc.perform(get("/actuator/configprops").with(user(adminUser.username).roles("ADMIN")))
            .andExpect(status().isNotFound)
    }
}
