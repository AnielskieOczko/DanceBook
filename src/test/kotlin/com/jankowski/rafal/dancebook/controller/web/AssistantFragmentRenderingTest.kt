package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.AssistantMessageView
import com.jankowski.rafal.dancebook.dto.AssistantTurn
import com.jankowski.rafal.dancebook.dto.ConversationView
import com.jankowski.rafal.dancebook.dto.ResultCard
import com.jankowski.rafal.dancebook.model.AssistantConversation
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantConversationService
import com.jankowski.rafal.dancebook.service.AssistantService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

/** Pins the ids the assistant widget swaps into and the shapes the controller hands its fragments. */
@WebMvcTest(
    controllers = [AssistantWebController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
        OAuth2ClientWebSecurityAutoConfiguration::class
    ],
    excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [SecurityConfig::class])]
)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = ["google.ai.api-key=test-key"])
@Import(RichTextServiceImpl::class)
class AssistantFragmentRenderingTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var assistantService: AssistantService
    @MockBean private lateinit var conversationService: AssistantConversationService
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    @Test
    fun `start fragment root id and conversation id reset`() {
        mockMvc.perform(get("/assistant/start").param("pageType", "HOME").header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistantStart\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
    }

    @Test
    fun `turn fragment renders both bubbles, a card, and the out-of-band conversation id`() {
        val id = UUID.randomUUID()
        `when`(assistantService.send(id, "sway?", com.jankowski.rafal.dancebook.dto.PageContext())).thenReturn(
            AssistantTurn(
                id,
                listOf(
                    AssistantMessageView(AssistantRole.USER, "sway?"),
                    AssistantMessageView(
                        AssistantRole.ASSISTANT, "One note.",
                        listOf(ResultCard("note", "n1", "Rise and fall", "Waltz", "sway on two", "/materials/n1", listOf("Natural Turn")))
                    )
                ),
                true
            )
        )
        mockMvc.perform(
            post("/assistant/messages").with(csrf()).header("HX-Request", "true")
                .param("conversationId", id.toString()).param("text", "sway?")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("sway?")))
            .andExpect(content().string(containsString("href=\"/materials/n1\"")))
            .andExpect(content().string(containsString("id=\"assistantConversationId\"")))
    }

    @Test
    fun `history fragment root id`() {
        val c = AssistantConversation().apply { id = UUID.randomUUID(); title = "Sway notes"; updatedAt = LocalDateTime.of(2026, 9, 29, 10, 0) }
        `when`(conversationService.list()).thenReturn(listOf(c))
        mockMvc.perform(get("/assistant/conversations").param("pageType", "HOME").header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistantHistory\"")))
            .andExpect(content().string(containsString("Sway notes")))
    }

    @Test
    fun `conversation fragment root id`() {
        val id = UUID.randomUUID()
        `when`(assistantService.conversation(id)).thenReturn(
            ConversationView(id, "Sway notes", listOf(AssistantMessageView(AssistantRole.USER, "hello")))
        )
        mockMvc.perform(get("/assistant/conversations/$id").header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistantConversation\"")))
    }
}
