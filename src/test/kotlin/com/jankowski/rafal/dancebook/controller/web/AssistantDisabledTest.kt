package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.service.AssistantService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** With no API key the assistant is not broken, it is absent: no beans, no routes, no markup. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key="])
class AssistantDisabledTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private fun signedIn(): org.springframework.test.web.servlet.request.RequestPostProcessor {
        val u = appUserRepository.save(AppUser().apply {
            username = "nokey-${UUID.randomUUID()}"; displayName = "No Key"; password = "x"; role = Role.USER
        })
        return user(u.username).roles("USER")
    }

    @Test
    fun `no assistant bean exists`() {
        assertTrue(context.getBeansOfType(AssistantService::class.java).isEmpty())
        assertTrue(context.getBeansOfType(AssistantWebController::class.java).isEmpty())
    }

    @Test
    fun `every assistant route is a 404`() {
        val who = signedIn()
        mockMvc.perform(get("/assistant/start").with(who)).andExpect(status().isNotFound)
        mockMvc.perform(get("/assistant/conversations").with(who)).andExpect(status().isNotFound)
        mockMvc.perform(get("/assistant/conversations/${UUID.randomUUID()}").with(who)).andExpect(status().isNotFound)
        mockMvc.perform(post("/assistant/messages").with(csrf()).with(who).param("text", "hi")).andExpect(status().isNotFound)
    }

    @Test
    fun `pages carry no assistant markup and load no assistant script`() {
        mockMvc.perform(get("/").with(signedIn()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("assistantWidget"))))
            .andExpect(content().string(not(containsString("assistant.js"))))
    }
}
