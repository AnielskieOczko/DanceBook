package com.jankowski.rafal.dancebook.security

import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key="])
class HtmxAuthenticationWebTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var googleDriveService: GoogleDriveService

    @MockitoBean
    private lateinit var calendarClient: GoogleCalendarClient

    @Test
    fun `unauthenticated normal request to dance-figures redirects to login`() {
        mockMvc.perform(get("/dance-figures"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
    }

    @Test
    fun `unauthenticated normal request to materials redirects to login`() {
        mockMvc.perform(get("/materials"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("http://localhost/login"))
    }

    @Test
    fun `unauthenticated HTMX request to dance-figures returns 401 with HX-Redirect header`() {
        mockMvc.perform(
            get("/dance-figures")
                .header("HX-Request", "true")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("HX-Redirect", "/login"))
    }

    @Test
    fun `unauthenticated HTMX request to materials returns 401 with HX-Redirect header`() {
        mockMvc.perform(
            get("/materials")
                .header("HX-Request", "true")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("HX-Redirect", "/login"))
    }

    @Test
    fun `unauthenticated HTMX post request returns 401 with HX-Redirect header`() {
        mockMvc.perform(
            post("/dance-figures")
                .header("HX-Request", "true")
                .with(csrf())
        )
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("HX-Redirect", "/login"))
    }
}
