package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.ScriptedChatModel
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
@Import(AssistantWidgetRenderingTest.ScriptedModelConfig::class)
class AssistantWidgetRenderingTest {

    @TestConfiguration
    class ScriptedModelConfig {
        @Bean
        @Primary
        fun scriptedChatModel(): ChatModel = ScriptedChatModel(listOf(ScriptedChatModel.text("ok")))
    }

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var alice: AppUser

    @BeforeEach
    fun setUp() {
        alice = appUserRepository.save(AppUser().apply {
            username = "alice-${UUID.randomUUID()}"; displayName = "Alice"; password = "x"; role = Role.USER
        })
    }

    private fun asAlice() = user(alice.username).roles("USER")

    @Test
    fun `home renders the bar, the dialog, the header button and the script`() {
        mockMvc.perform(get("/").with(asAlice()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistantBar\"")))
            .andExpect(content().string(containsString("<dialog id=\"assistantSurface\"")))
            .andExpect(content().string(containsString("fixed inset-0")))
            .andExpect(content().string(containsString("md:w-[400px]")))
            .andExpect(content().string(containsString("data-assistant-open")))
            .andExpect(content().string(containsString("/js/assistant.js")))
            .andExpect(content().string(containsString("data-page-type=\"HOME\"")))
            .andExpect(content().string(containsString("Looking at:")))
    }

    @Test
    fun `assistant bar uses rounded-pill, ambient shadow and focus-within ring tokens`() {
        val result = mockMvc.perform(get("/").with(asAlice()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = org.jsoup.Jsoup.parse(result.response.contentAsString)
        val bar = doc.selectFirst("#assistantBar")
        org.junit.jupiter.api.Assertions.assertNotNull(bar, "#assistantBar must be present")
        org.junit.jupiter.api.Assertions.assertTrue(bar!!.hasClass("rounded-pill"), "#assistantBar must use rounded-pill token")
        org.junit.jupiter.api.Assertions.assertTrue(bar.hasClass("shadow-ambient"), "#assistantBar must use shadow-ambient token")
        org.junit.jupiter.api.Assertions.assertTrue(bar.className().contains("focus-within:ring-3"), "#assistantBar must draw 3px focus ring on focus-within")

        val kbd = bar.selectFirst("kbd")
        org.junit.jupiter.api.Assertions.assertNotNull(kbd, "kbd shortcut hint must be present")
        org.junit.jupiter.api.Assertions.assertTrue(kbd!!.hasClass("rounded-pill"), "kbd shortcut hint must use rounded-pill token")
    }

    @Test
    fun `a figure page names the figure, resolved by the server`() {
        val category = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        val waltz = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; this.category = category })
        val figure = danceFigureRepository.save(DanceFigure().apply {
            name = "Assistant Test Turn ${UUID.randomUUID()}"; danceType = waltz; danceClass = DanceClass.D
        })

        mockMvc.perform(get("/dance-figures/${figure.id}").with(asAlice()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("data-page-type=\"FIGURE\"")))
            .andExpect(content().string(containsString("data-page-id=\"${figure.id}\"")))
            .andExpect(content().string(containsString("Looking at: <span>${figure.name}</span>")))
    }

    @Test
    fun `the mic ships hidden, so a browser without speech recognition never sees it`() {
        mockMvc.perform(get("/").with(asAlice()))
            .andExpect(content().string(matchesPattern("(?s).*<button[^>]*data-assistant-mic[^>]*hidden[^>]*>.*")))
    }
}
