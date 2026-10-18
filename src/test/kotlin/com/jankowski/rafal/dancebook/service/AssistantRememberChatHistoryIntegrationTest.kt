package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.controller.web.AssistantWebIntegrationTest
import com.jankowski.rafal.dancebook.dto.AssistantPreferencesUpdateRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceClass
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
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpSessionEvent
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
@Import(AssistantRememberChatHistoryIntegrationTest.ScriptedModelConfig::class)
class AssistantRememberChatHistoryIntegrationTest {

    @TestConfiguration
    class ScriptedModelConfig {
        @Bean
        @Primary
        fun scriptedChatModel(): ChatModel = ScriptedChatModel(listOf(
            ScriptedChatModel.text("First answer."),
            ScriptedChatModel.text("Second answer.")
        ))
    }

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var conversationRepository: AssistantConversationRepository
    @Autowired private lateinit var messageRepository: AssistantMessageRepository
    @Autowired private lateinit var draftRepository: AssistantDraftRepository
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var assistantService: AssistantService
    @Autowired private lateinit var assistantPreferencesService: AssistantPreferencesService
    @Autowired private lateinit var draftService: AssistantDraftService
    @Autowired private lateinit var draftCodec: AssistantDraftCodec
    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var cleanup: AssistantConversationCleanup

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var googleDriveService: GoogleDriveService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var danceType: DanceType

    private fun newUser(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"
        displayName = name
        password = "x"
        role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        `when`(googleDriveService.listFilesInFolder()).thenReturn(emptyList())
        draftRepository.deleteAll()
        messageRepository.deleteAll()
        conversationRepository.deleteAll()

        alice = newUser("alice")
        bob = newUser("bob")

        val cat = danceCategoryRepository.findAll().firstOrNull()
            ?: danceCategoryRepository.save(com.jankowski.rafal.dancebook.model.DanceCategory().apply { name = "Standard" })
        danceType = danceTypeRepository.findAll().firstOrNull()
            ?: danceTypeRepository.save(DanceType().apply { name = "Slow Waltz"; category = cat })
    }

    private fun asAlice() = user(alice.username).roles("USER")
    private fun asBob() = user(bob.username).roles("USER")

    private fun <T> runAs(user: AppUser, block: () -> T): T {
        val auth = UsernamePasswordAuthenticationToken(user.username, null, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")))
        val previous = SecurityContextHolder.getContext().authentication
        try {
            SecurityContextHolder.getContext().authentication = auth
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    @Test
    fun `with remember history off, new conversation works normally while signed in, but is absent from history rail`() {
        assistantPreferencesService.updatePreferences(alice, AssistantPreferencesUpdateRequest(rememberChatHistory = false))

        mockMvc.perform(
            post("/assistant/messages").with(csrf()).with(asAlice())
                .header("HX-Request", "true")
                .param("text", "Hello assistant").param("pageType", "HOME")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Hello assistant")))
            .andExpect(content().string(containsString("First answer.")))

        val conv = conversationRepository.findAll().single()
        assertTrue(conv.ephemeral, "Conversation must be marked ephemeral when rememberChatHistory is off")
        val convId = conv.id!!

        // Follow-up message keeps context
        mockMvc.perform(
            post("/assistant/messages").with(csrf()).with(asAlice())
                .header("HX-Request", "true")
                .param("text", "Second message").param("pageType", "HOME")
                .param("conversationId", convId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Second message")))
            .andExpect(content().string(containsString("Second answer.")))

        val messages = messageRepository.findByConversationIdOrderByPositionAsc(convId)
        assertEquals(4, messages.size, "Should have 2 user and 2 assistant messages")

        // Ephemeral conversation is absent from history rail
        mockMvc.perform(get("/assistant/conversations").with(asAlice()).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Hello assistant"))))
            .andExpect(content().string(containsString("No conversations yet")))
    }

    @Test
    fun `drafts produced in session-only conversation work for Save and Edit in form`() {
        runAs(alice) {
            assistantPreferencesService.updatePreferences(alice, AssistantPreferencesUpdateRequest(rememberChatHistory = false))

            // Start ephemeral conversation directly
            val startReq = MockHttpServletRequest()
            val session = MockHttpSession(null, "test-session-123")
            startReq.setSession(session)
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                org.springframework.web.context.request.ServletRequestAttributes(startReq)
            )

            val conv = conversationService.start("Need figure draft", ephemeral = true)
            val convId = conv.id!!

            val draftPayload = draftCodec.toPayload(DanceFigureRequest(
                name = "Unique Ephemeral Figure",
                danceTypeId = danceType.id,
                danceClass = DanceClass.C
            ))
            val draftId = draftService.create(convId, DraftKind.FIGURE, draftPayload)

            // Save works
            mockMvc.perform(
                post("/assistant/drafts/$draftId/save").with(csrf()).with(asAlice()).header("HX-Request", "true")
            )
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Saved")))

            val savedDraft = draftRepository.findById(draftId).get()
            assertEquals(DraftStatus.SAVED, savedDraft.status)
            assertNotNull(savedDraft.savedEntityId)
            assertTrue(danceFigureRepository.existsById(savedDraft.savedEntityId!!))

            // Another draft for Edit in form
            val draft2Payload = draftCodec.toPayload(DanceFigureRequest(
                name = "Unique Ephemeral Figure 2",
                danceTypeId = danceType.id,
                danceClass = DanceClass.C
            ))
            val draft2Id = runAs(alice) { draftService.create(convId, DraftKind.FIGURE, draft2Payload) }

            mockMvc.perform(
                post("/assistant/drafts/$draft2Id/edit").with(csrf()).with(asAlice()).header("HX-Request", "true")
            )
                .andExpect(status().isOk)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("HX-Redirect", "/dance-figures/new?fromDraft=$draft2Id"))

            val discardedDraft = draftRepository.findById(draft2Id).get()
            assertEquals(DraftStatus.DISCARDED, discardedDraft.status)
        }
    }

    @Test
    fun `saved conversations remain listed and turning switch back on saves new conversations without affecting open ones`() {
        runAs(alice) {
            // Step 1: User has rememberChatHistory on (default). Start conversation 1.
            val conv1 = conversationService.start("Saved conversation 1", ephemeral = false)
            val conv1Id = conv1.id!!

            // Step 2: Turn off rememberChatHistory. Start conversation 2.
            assistantPreferencesService.updatePreferences(alice, AssistantPreferencesUpdateRequest(rememberChatHistory = false))
            val conv2 = conversationService.start("Ephemeral conversation 2", ephemeral = true)
            val conv2Id = conv2.id!!

            // History rail lists conv1, but not conv2
            val list = conversationService.list()
            assertEquals(1, list.size)
            assertEquals(conv1Id, list.first().id)

            // Step 3: Turn rememberChatHistory back ON.
            assistantPreferencesService.updatePreferences(alice, AssistantPreferencesUpdateRequest(rememberChatHistory = true))

            // Open conversation 2 is still ephemeral (not retroactively saved)
            assertTrue(conversationRepository.findById(conv2Id).get().ephemeral)
            assertEquals(1, conversationService.list().size)

            // New conversation 3 is saved
            val conv3 = conversationService.start("Saved conversation 3", ephemeral = false)
            val conv3Id = conv3.id!!
            assertFalse(conversationRepository.findById(conv3Id).get().ephemeral)

            val updatedList = conversationService.list()
            assertEquals(2, updatedList.size)
            assertTrue(updatedList.any { it.id == conv1Id })
            assertTrue(updatedList.any { it.id == conv3Id })
            assertFalse(updatedList.any { it.id == conv2Id })

            // Earlier saved conversation can still be deleted
            conversationService.delete(conv1Id)
            assertEquals(1, conversationService.list().size)
            assertEquals(conv3Id, conversationService.list().first().id)
        }
    }

    @Test
    fun `start stores sessionId only on ephemeral conversations and null on saved conversations`() {
        val req = MockHttpServletRequest()
        val session = MockHttpSession(null, "session-test-id")
        req.setSession(session)
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            org.springframework.web.context.request.ServletRequestAttributes(req)
        )

        val savedConv = runAs(alice) { conversationService.start("Saved thread", ephemeral = false) }
        val ephemeralConv = runAs(alice) { conversationService.start("Ephemeral thread", ephemeral = true) }

        assertNull(savedConv.sessionId, "Saved conversation must not store a session id")
        assertEquals("session-test-id", ephemeralConv.sessionId, "Ephemeral conversation must store session id")
    }

    @Test
    fun `saved conversation remains listed after logout, while ephemeral conversation from same session is removed`() {
        val sessionA = MockHttpSession(null, "session-logout-test")
        val req = MockHttpServletRequest()
        req.setSession(sessionA)
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            org.springframework.web.context.request.ServletRequestAttributes(req)
        )

        val savedConv = runAs(alice) { conversationService.start("Saved before logout", ephemeral = false) }
        val ephemeralConv = runAs(alice) { conversationService.start("Ephemeral before logout", ephemeral = true) }

        // Both exist before logout, but list() only shows saved
        val beforeList = runAs(alice) { conversationService.list() }
        assertTrue(beforeList.any { it.id == savedConv.id })
        assertFalse(beforeList.any { it.id == ephemeralConv.id })

        // Alice logs out
        val auth = UsernamePasswordAuthenticationToken(alice.username, "x")
        cleanup.logout(req, MockHttpServletResponse(), auth)

        // Saved conversation is still listed and in DB
        val afterList = runAs(alice) { conversationService.list() }
        assertTrue(afterList.any { it.id == savedConv.id }, "Saved conversation must remain listed after logout")
        assertTrue(conversationRepository.findById(savedConv.id!!).isPresent)

        // Ephemeral conversation from the same session is deleted
        assertTrue(conversationRepository.findById(ephemeralConv.id!!).isEmpty, "Ephemeral conversation must be deleted on logout")
    }

    @Test
    fun `saved conversation remains listed after session expiry, while ephemeral conversation from same session is removed`() {
        val sessionB = MockHttpSession(null, "session-expiry-test")
        val req = MockHttpServletRequest()
        req.setSession(sessionB)
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            org.springframework.web.context.request.ServletRequestAttributes(req)
        )

        val savedConv = runAs(alice) { conversationService.start("Saved before expiry", ephemeral = false) }
        val ephemeralConv = runAs(alice) { conversationService.start("Ephemeral before expiry", ephemeral = true) }

        // Session expires
        cleanup.sessionDestroyed(HttpSessionEvent(sessionB))

        // Saved conversation is still listed and in DB
        val afterList = runAs(alice) { conversationService.list() }
        assertTrue(afterList.any { it.id == savedConv.id }, "Saved conversation must remain listed after session expiry")
        assertTrue(conversationRepository.findById(savedConv.id!!).isPresent)

        // Ephemeral conversation from same session is deleted
        assertTrue(conversationRepository.findById(ephemeralConv.id!!).isEmpty, "Ephemeral conversation must be deleted on session expiry")
    }

    @Test
    fun `saved conversation remains listed after startup cleanup, while ephemeral conversation is removed`() {
        val savedConv = runAs(alice) { conversationService.start("Saved before restart", ephemeral = false) }
        val ephemeralConv = runAs(alice) { conversationService.start("Ephemeral before restart", ephemeral = true) }

        // Application startup event fires
        cleanup.onStartup()

        // Saved conversation is still listed and in DB
        val afterList = runAs(alice) { conversationService.list() }
        assertTrue(afterList.any { it.id == savedConv.id }, "Saved conversation must remain listed after startup cleanup")
        assertTrue(conversationRepository.findById(savedConv.id!!).isPresent)

        // Ephemeral conversation is deleted
        assertTrue(conversationRepository.findById(ephemeralConv.id!!).isEmpty, "Ephemeral conversation must be deleted on startup")
    }

    @Test
    fun `cleanup queries never delete saved conversations even if sessionId is present`() {
        // Edge case: saved conversation that happens to have a sessionId
        val savedWithSession = conversationRepository.save(com.jankowski.rafal.dancebook.model.AssistantConversation().apply {
            owner = alice
            title = "Saved with session"
            ephemeral = false
            sessionId = "edge-case-session"
        })
        val ephemeralWithSession = conversationRepository.save(com.jankowski.rafal.dancebook.model.AssistantConversation().apply {
            owner = alice
            title = "Ephemeral with session"
            ephemeral = true
            sessionId = "edge-case-session"
        })

        val req = MockHttpServletRequest()
        val session = MockHttpSession(null, "edge-case-session")
        req.setSession(session)
        val auth = UsernamePasswordAuthenticationToken(alice.username, "x")

        // Logout
        cleanup.logout(req, MockHttpServletResponse(), auth)

        assertTrue(conversationRepository.findById(savedWithSession.id!!).isPresent, "Saved conversation with sessionId must NOT be deleted by logout")
        assertTrue(conversationRepository.findById(ephemeralWithSession.id!!).isEmpty, "Ephemeral conversation must be deleted")
    }
}
