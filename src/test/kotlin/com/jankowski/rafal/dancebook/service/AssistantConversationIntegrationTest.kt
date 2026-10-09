package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class AssistantConversationIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var service: AssistantConversationService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var conversationRepository: AssistantConversationRepository
    @Autowired private lateinit var messageRepository: AssistantMessageRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser

    private fun newUser(name: String) = appUserRepository.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"
        displayName = name
        password = "x"
        role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        messageRepository.deleteAll()
        conversationRepository.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
        `when`(appUserService.getCurrentUser()).thenReturn(alice)
    }

    private fun actAs(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
    }

    @Test
    fun `user B cannot list, open, read, continue, rename or delete user A's conversation`() {
        val conversation = service.start("Which notes mention sway?")
        val id = conversation.id!!
        service.append(id, AssistantRole.USER, "Which notes mention sway?")

        actAs(bob)

        assertTrue(service.list().isEmpty())
        assertThrows(EntityNotFoundException::class.java) { service.findOwned(id) }
        assertThrows(EntityNotFoundException::class.java) { service.messages(id) }
        assertThrows(EntityNotFoundException::class.java) { service.recentContext(id) }
        assertThrows(EntityNotFoundException::class.java) { service.append(id, AssistantRole.USER, "hi") }
        assertThrows(EntityNotFoundException::class.java) { service.rename(id, "Mine now") }
        assertThrows(EntityNotFoundException::class.java) { service.delete(id) }

        actAs(alice)
        assertEquals("Which notes mention sway?", service.findOwned(id).title)
    }

    @Test
    fun `messages keep their order, and tool payload survives as JSON`() {
        val id = service.start("hello").id!!
        service.append(id, AssistantRole.USER, "hello")
        service.append(
            id, AssistantRole.TOOL, "search_figures",
            mutableMapOf("name" to "search_figures", "arguments" to "{\"query\":\"heel\"}",
                "result" to mapOf("total" to 1, "items" to listOf(mapOf("title" to "Heel Turn"))))
        )
        service.append(id, AssistantRole.ASSISTANT, "One figure matches.")

        val all = service.messages(id)
        assertEquals(listOf(AssistantRole.USER, AssistantRole.TOOL, AssistantRole.ASSISTANT), all.map { it.role })
        assertEquals("search_figures", all[1].toolPayload!!["name"])
        assertEquals(1, (all[1].toolPayload!!["result"] as Map<*, *>)["total"])

        val context = service.recentContext(id)
        assertEquals(listOf(AssistantRole.USER, AssistantRole.ASSISTANT), context.map { it.role })
    }

    @Test
    fun `recentContext keeps only the newest messages`() {
        val id = service.start("many").id!!
        repeat(30) { service.append(id, AssistantRole.USER, "message $it") }
        val context = service.recentContext(id, 20)
        assertEquals(20, context.size)
        assertEquals("message 10", context.first().content)
        assertEquals("message 29", context.last().content)
    }

    @Test
    fun `title comes from the first message, capped at 80 characters, and rename validates`() {
        val long = "x".repeat(200)
        val conversation = service.start("  $long  ")
        assertEquals(80, conversation.title.length)
        assertThrows(IllegalArgumentException::class.java) { service.rename(conversation.id!!, "   ") }
        assertEquals("Sway notes", service.rename(conversation.id!!, "  Sway   notes ").title)
    }

    @Test
    fun `delete removes the conversation and its messages`() {
        val id = service.start("bye").id!!
        service.append(id, AssistantRole.USER, "bye")
        service.delete(id)
        assertEquals(0, messageRepository.countByConversationId(id))
        assertTrue(conversationRepository.findById(id).isEmpty)
    }

    @Test
    fun `two messages cannot claim the same position`() {
        val id = service.start("race").id!!
        val first = service.append(id, AssistantRole.USER, "one")
        val clash = com.jankowski.rafal.dancebook.model.AssistantMessage().apply {
            conversation = conversationRepository.findById(id).get()
            position = first.position
            role = AssistantRole.USER
            content = "two"
        }
        assertThrows(DataIntegrityViolationException::class.java) { messageRepository.saveAndFlush(clash) }
    }
}
