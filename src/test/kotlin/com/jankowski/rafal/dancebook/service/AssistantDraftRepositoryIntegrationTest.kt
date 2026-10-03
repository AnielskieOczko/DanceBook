package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantConversation
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class AssistantDraftRepositoryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var transactions: TransactionTemplate

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        drafts.deleteAll()
        messages.deleteAll()
        conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
    }

    private fun aliceDraft(): AssistantDraft {
        val conversation = conversations.save(AssistantConversation().apply {
            owner = alice; title = "wrap up"
        })
        return drafts.save(AssistantDraft().apply {
            this.conversation = conversation
            kind = DraftKind.NOTE
            payload = mutableMapOf("name" to "Tuesday class", "figureIds" to listOf("f1"))
        })
    }

    @Test
    fun `a draft keeps its jsonb payload and starts pending`() {
        val saved = aliceDraft()

        val loaded = drafts.findOwned(saved.id!!, alice.id!!)!!

        assertEquals(DraftStatus.PENDING, loaded.status)
        assertEquals("Tuesday class", loaded.payload["name"])
        assertEquals(listOf("f1"), loaded.payload["figureIds"])
        assertNull(loaded.savedEntityId)
        assertNull(loaded.message)
    }

    @Test
    fun `only the conversation's owner can find or lock the draft`() {
        val saved = aliceDraft()

        assertNotNull(drafts.findOwned(saved.id!!, alice.id!!))
        assertNull(drafts.findOwned(saved.id!!, bob.id!!))
        // A row lock only exists inside a transaction, which is where the store always calls it.
        assertNull(transactions.execute { drafts.findOwnedForUpdate(saved.id!!, bob.id!!) })
        assertNotNull(transactions.execute { drafts.findOwnedForUpdate(saved.id!!, alice.id!!) })
    }

    @Test
    fun `deleting the conversation deletes its drafts`() {
        val saved = aliceDraft()

        conversations.deleteById(saved.conversation!!.id!!)

        assertFalse(drafts.findById(saved.id!!).isPresent)
    }
}
