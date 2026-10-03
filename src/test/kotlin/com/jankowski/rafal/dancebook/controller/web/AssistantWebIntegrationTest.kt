package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import com.jankowski.rafal.dancebook.service.AssistantConversationService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import com.jankowski.rafal.dancebook.service.ScriptedChatModel
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
@Import(AssistantWebIntegrationTest.ScriptedModelConfig::class)
class AssistantWebIntegrationTest {

    @TestConfiguration
    class ScriptedModelConfig {
        /** Wins over the real Gemini model, which the test key still creates but never calls. */
        @Bean
        @Primary
        fun scriptedChatModel(): ChatModel = ScriptedChatModel(listOf(ScriptedChatModel.text("Two notes mention sway.")))
    }

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var conversationService: AssistantConversationService

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser

    private fun newUser(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        messages.deleteAll()
        conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
    }

    private fun send(who: AppUser, text: String, conversationId: UUID? = null) =
        mockMvc.perform(
            post("/assistant/messages").with(csrf()).with(user(who.username).roles("USER"))
                .header("HX-Request", "true")
                .param("text", text).param("pageType", "HOME")
                .apply { if (conversationId != null) param("conversationId", conversationId.toString()) }
        )

    private fun aliceConversation(): UUID {
        send(alice, "Which notes mention sway?").andExpect(status().isOk)
        return conversations.findByOwnerIdOrderByUpdatedAtDesc(alice.id!!).single().id!!
    }

    @Test
    fun `a message makes a saved conversation and returns the bubbles, the sent trigger and the new id`() {
        send(alice, "Which notes mention sway?")
            .andExpect(status().isOk)
            .andExpect(header().string("HX-Trigger", "assistant-sent"))
            .andExpect(content().string(containsString("data-conversation-id=")))
            .andExpect(content().string(containsString("Which notes mention sway?")))
            .andExpect(content().string(containsString("Two notes mention sway.")))

        val saved = conversations.findByOwnerIdOrderByUpdatedAtDesc(alice.id!!).single()
        assertEquals("Which notes mention sway?", saved.title)
    }

    @Test
    fun `history lists, reopens after a reload, renames and deletes`() {
        val id = aliceConversation()
        val as1 = user(alice.username).roles("USER")

        mockMvc.perform(get("/assistant/conversations").with(as1).header("HX-Request", "true"))
            .andExpect(status().isOk).andExpect(content().string(containsString("Which notes mention sway?")))

        mockMvc.perform(get("/assistant/conversations/$id").with(as1).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Two notes mention sway.")))
            .andExpect(content().string(containsString("data-conversation-id=\"$id\"")))

        mockMvc.perform(post("/assistant/conversations/$id/rename").with(csrf()).with(as1).param("title", "Sway notes"))
            .andExpect(status().isOk).andExpect(content().string(containsString("Sway notes")))
        assertEquals("Sway notes", conversations.findById(id).get().title)

        mockMvc.perform(post("/assistant/conversations/$id/delete").with(csrf()).with(as1))
            .andExpect(status().isOk).andExpect(content().string(not(containsString("Sway notes"))))
        assertEquals(0, messages.countByConversationId(id))
    }

    @Test
    fun `a blank rename shows an error and keeps the old name`() {
        val id = aliceConversation()
        mockMvc.perform(post("/assistant/conversations/$id/rename").with(csrf()).with(user(alice.username).roles("USER")).param("title", "   "))
            .andExpect(status().isOk).andExpect(content().string(containsString("A conversation needs a name.")))
        assertEquals("Which notes mention sway?", conversations.findById(id).get().title)
    }

    @Test
    fun `user B gets 404 on every route for user A's conversation, and nothing changes`() {
        val id = aliceConversation()
        val asBob = user(bob.username).roles("USER")

        mockMvc.perform(get("/assistant/conversations/$id").with(asBob)).andExpect(status().isNotFound)
        mockMvc.perform(post("/assistant/conversations/$id/rename").with(csrf()).with(asBob).param("title", "Mine"))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/assistant/conversations/$id/delete").with(csrf()).with(asBob)).andExpect(status().isNotFound)
        send(bob, "continue theirs", id).andExpect(status().isNotFound)

        mockMvc.perform(get("/assistant/conversations").with(asBob))
            .andExpect(status().isOk).andExpect(content().string(not(containsString("Which notes mention sway?"))))

        val still = conversations.findById(id).get()
        assertEquals("Which notes mention sway?", still.title)
        assertEquals(2, messages.countByConversationId(id), "only Alice's own user and assistant messages")
    }

    @Test
    fun `sending into a conversation that was just deleted is a 404, not a fresh conversation`() {
        val id = aliceConversation()
        conversations.deleteById(id)
        send(alice, "still there?", id).andExpect(status().isNotFound)
        assertEquals(0, conversations.findByOwnerIdOrderByUpdatedAtDesc(alice.id!!).size)
    }

    @Test
    fun `a blank message is a 400 and stores nothing`() {
        send(alice, "   ").andExpect(status().isBadRequest)
        assertEquals(0, conversations.findByOwnerIdOrderByUpdatedAtDesc(alice.id!!).size)
    }

    @Test
    fun `the start fragment carries chips and clears the conversation id`() {
        mockMvc.perform(get("/assistant/start").param("pageType", "FIGURE").with(user(alice.username).roles("USER")))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Which of my notes mention this figure?")))
            .andExpect(content().string(containsString("data-conversation-id=\"\"")))
    }

    @Test
    fun `signed-out visitors go to login`() {
        mockMvc.perform(get("/assistant/start")).andExpect(status().is3xxRedirection)
    }
}
