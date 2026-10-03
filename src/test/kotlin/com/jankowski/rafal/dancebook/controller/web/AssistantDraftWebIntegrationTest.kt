package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantConversationService
import com.jankowski.rafal.dancebook.service.AssistantDraftCodec
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftWebIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var danceFigures: DanceFigureRepository
    @Autowired private lateinit var danceTypes: DanceTypeRepository
    @Autowired private lateinit var danceCategories: DanceCategoryRepository
    @Autowired private lateinit var draftService: AssistantDraftService
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var codec: AssistantDraftCodec

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var danceType: DanceType

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    private fun actAs(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)
        // The layout's unread-notification count reads the user by id.
        `when`(appUserService.findById(user.id!!)).thenReturn(user)
    }

    @BeforeEach
    fun setUp() {
        drafts.deleteAll(); messages.deleteAll(); conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
        actAs(alice)
        val category = danceCategories.save(DanceCategory().apply { name = "Web Cat " + UUID.randomUUID() })
        danceType = danceTypes.save(DanceType().apply { name = "Web Style " + UUID.randomUUID(); this.category = category })
    }

    private fun aliceFigureDraft(name: String): UUID {
        val conversation = conversationService.start("draft")
        return draftService.create(conversation.id!!, DraftKind.FIGURE, codec.toPayload(DanceFigureRequest(name = name, danceTypeId = danceType.id)))
    }

    @Test
    fun `Save over htmx creates the figure once and swaps in the saved card - a second Save shows the same saved card and creates nothing`() {
        val id = aliceFigureDraft("Web Heel Turn")
        val asAlice = user(alice.username).roles("USER")

        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Open what was created")))
        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Open what was created")))
        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice))
            .andExpect(status().isConflict)

        assertEquals(1, danceFigures.findAll().count { it.name == "Web Heel Turn" })
    }

    @Test
    fun `user B gets 404 for Save and Edit in form on user A's draft, and nothing changes`() {
        val id = aliceFigureDraft("Web Private Figure")
        actAs(bob)
        val asBob = user(bob.username).roles("USER")

        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asBob)).andExpect(status().isNotFound)
        mockMvc.perform(post("/assistant/drafts/$id/edit").with(csrf()).with(asBob)).andExpect(status().isNotFound)

        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
        assertTrue(danceFigures.findAll().none { it.name == "Web Private Figure" })
    }

    @Test
    fun `Edit in form redirects htmx to the prefilled form and greys the draft out`() {
        val id = aliceFigureDraft("Web Edit Figure")

        mockMvc.perform(post("/assistant/drafts/$id/edit").with(csrf()).with(user(alice.username).roles("USER")).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/dance-figures/new?fromDraft=$id"))

        assertEquals(DraftStatus.DISCARDED, drafts.findById(id).get().status)
    }
}
