package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftStoreIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var store: AssistantDraftStore
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

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
        `when`(appUserService.getCurrentUser()).thenReturn(alice)
    }

    private fun actAs(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
    }

    private fun aliceDraft(): UUID {
        val conversation = conversationService.start("wrap up")
        return store.create(conversation.id!!, DraftKind.FIGURE, mutableMapOf("name" to "Heel Turn")).id!!
    }

    @Test
    fun `claiming moves a pending draft to saved, and a second claim is refused`() {
        val id = aliceDraft()

        assertEquals(DraftStatus.SAVED, store.claim(id).status)
        val refused = assertThrows(DraftNotPendingException::class.java) { store.claim(id) }
        assertEquals(DraftStatus.SAVED, refused.status)
    }

    @Test
    fun `two saves at the same moment - exactly one claims the draft`() {
        val id = aliceDraft()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Boolean> {
                    start.await()
                    try { store.claim(id); true } catch (e: DraftNotPendingException) { false }
                }
            }
            start.countDown()
            val won = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, won.count { it }, "one claim wins, the other is refused: $won")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `finish records what was created, and release puts a failed save back to pending with the error`() {
        val id = aliceDraft()
        store.claim(id)
        store.release(id, "Name is too short")

        val released = store.findOwned(id)
        assertEquals(DraftStatus.PENDING, released.status)
        assertEquals("Name is too short", released.notice)

        store.claim(id)
        val entity = UUID.randomUUID()
        store.finish(id, entity, "Saved, but not linked")
        val finished = store.findOwned(id)
        assertEquals(DraftStatus.SAVED, finished.status)
        assertEquals(entity, finished.savedEntityId)
        assertEquals("Saved, but not linked", finished.notice)
    }

    @Test
    fun `release never reopens a draft that was finished`() {
        val id = aliceDraft()
        store.claim(id)
        store.finish(id, UUID.randomUUID(), null)

        store.release(id, "late failure")

        assertEquals(DraftStatus.SAVED, store.findOwned(id).status)
    }

    @Test
    fun `opening in the form discards a pending draft, which can then no longer be saved`() {
        val id = aliceDraft()

        assertEquals(DraftStatus.DISCARDED, store.discardForForm(id).status)
        assertEquals(DraftStatus.DISCARDED, store.discardForForm(id).status, "opening twice is fine")
        assertThrows(DraftNotPendingException::class.java) { store.claim(id) }
    }

    @Test
    fun `a saved draft cannot be opened in the form, and is not offered to the form`() {
        val id = aliceDraft()
        store.claim(id)

        assertThrows(DraftNotPendingException::class.java) { store.discardForForm(id) }
        assertNull(store.findOpenOrNull(id, DraftKind.FIGURE))
    }

    @Test
    fun `a pending or discarded draft is offered to the form of its own kind only`() {
        val id = aliceDraft()

        assertNotNull(store.findOpenOrNull(id, DraftKind.FIGURE))
        assertNull(store.findOpenOrNull(id, DraftKind.NOTE))
        store.discardForForm(id)
        assertNotNull(store.findOpenOrNull(id, DraftKind.FIGURE))
    }

    @Test
    fun `user B cannot find, claim, discard or attach to user A's draft`() {
        val id = aliceDraft()
        val message = conversationService.append(
            drafts.findById(id).get().conversation!!.id!!, AssistantRole.TOOL, "draft_figure"
        )
        actAs(bob)

        assertThrows(EntityNotFoundException::class.java) { store.findOwned(id) }
        assertThrows(EntityNotFoundException::class.java) { store.claim(id) }
        assertThrows(EntityNotFoundException::class.java) { store.discardForForm(id) }
        assertThrows(EntityNotFoundException::class.java) { store.attach(id, message.id!!) }
        assertNull(store.findOpenOrNull(id, DraftKind.FIGURE))
        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
    }

    @Test
    fun `a draft cannot be created in someone else's conversation`() {
        val conversation = conversationService.start("mine")
        actAs(bob)

        assertThrows(EntityNotFoundException::class.java) {
            store.create(conversation.id!!, DraftKind.NOTE, mutableMapOf("name" to "x"))
        }
    }

    @Test
    fun `attach links the draft to its tool message`() {
        val id = aliceDraft()
        val conversationId = drafts.findById(id).get().conversation!!.id!!
        val message = conversationService.append(conversationId, AssistantRole.TOOL, "draft_figure")

        store.attach(id, message.id!!)

        assertEquals(message.id, drafts.findById(id).get().message?.id)
    }
}
