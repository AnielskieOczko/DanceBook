package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

/** Save through the real services and database: the acceptance criteria that a mock cannot prove. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var service: AssistantDraftService
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var codec: AssistantDraftCodec
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var materials: MaterialRepository
    @Autowired private lateinit var trainingEvents: TrainingEventRepository
    @Autowired private lateinit var trainingRecords: TrainingRecordRepository
    @Autowired private lateinit var danceFigures: DanceFigureRepository
    @Autowired private lateinit var danceTypes: DanceTypeRepository
    @Autowired private lateinit var danceCategories: DanceCategoryRepository
    @Autowired private lateinit var transactions: TransactionTemplate

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var danceType: DanceType
    private lateinit var catalogFigure: DanceFigure
    private lateinit var session: TrainingEvent

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
        actAs(alice)
        val category = danceCategories.save(DanceCategory().apply { name = "Draft Cat " + UUID.randomUUID() })
        danceType = danceTypes.save(DanceType().apply { name = "Draft Style " + UUID.randomUUID(); this.category = category })
        catalogFigure = danceFigures.save(DanceFigure().apply {
            name = "Feather Step"; this.danceType = this@AssistantDraftServiceIntegrationTest.danceType; alternativeTiming = "S Q Q"
        })
        session = trainingEvents.save(TrainingEvent().apply {
            title = "Standard group class"
            startTime = LocalDateTime.now().minusDays(2)
            endTime = LocalDateTime.now().minusDays(2).plusHours(1)
            createdBy = alice
        })
    }

    /** Services read the current user two ways (`getCurrentUser` and `getCurrentUserOrNull`), so both are stubbed. */
    private fun actAs(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(user)
    }

    private fun draftOf(kind: DraftKind, request: Any): UUID {
        val conversation = conversationService.start("wrap up")
        return service.create(conversation.id!!, kind, codec.toPayload(request))
    }

    private fun wrapUpNote(title: String = "Tuesday class") = MaterialRequest(
        name = title, description = "<div>Head drops.</div>", version = 0,
        trainingEventId = session.id, figureIds = listOf(catalogFigure.id!!), markAttended = true
    )

    @Test
    fun `saving a wrap-up note creates the note, pins the figure, writes the attendance record and links the session`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote())

        val card = service.save(id)

        assertEquals(DraftStatus.SAVED, card.status, "notice: ${card.notice}")
        assertNull(card.notice)
        val noteId = UUID.fromString(card.savedUrl!!.removePrefix("/materials/"))
        transactions.executeWithoutResult {
            val note = materials.findById(noteId).get()
            assertEquals("Tuesday class", note.name)
            assertEquals(listOf(catalogFigure.id), note.figures.mapNotNull { it.danceFigure?.id })
            val saved = trainingEvents.findById(session.id!!).get()
            assertEquals(noteId, saved.material?.id, "the session is linked to the note")
            assertEquals(AttendanceStatus.ATTENDED, saved.attendanceFor(alice))
        }
        assertNotNull(trainingRecords.findByTrainingEventIdAndCreatedBy(session.id!!, alice), "the TrainingRecord is written")
    }

    @Test
    fun `a second Save of the same draft is refused and creates nothing more`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Only once"))
        service.save(id)

        assertThrows(DraftNotPendingException::class.java) { service.save(id) }

        assertEquals(1, materials.findAll().count { it.name == "Only once" })
    }

    @Test
    fun `user B cannot save, open in the form or even see user A's draft`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Private to Alice"))
        actAs(bob)

        assertThrows(EntityNotFoundException::class.java) { service.save(id) }
        assertThrows(EntityNotFoundException::class.java) { service.openInForm(id) }
        assertThrows(EntityNotFoundException::class.java) { service.view(id) }
        assertNull(service.noteForForm(id))
        assertEquals(0, materials.findAll().count { it.name == "Private to Alice" })
        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
    }

    @Test
    fun `a Save that fails leaves the draft pending with the error on the card, and nothing is created`() {
        val unknownType = UUID.randomUUID()
        val id = draftOf(DraftKind.FIGURE, DanceFigureRequest(name = "Ghost Figure", danceTypeId = unknownType))

        val card = service.save(id)

        assertEquals(DraftStatus.PENDING, card.status)
        assertNotNull(card.notice)
        assertNull(card.savedUrl)
        assertTrue(danceFigures.findAll().none { it.name == "Ghost Figure" })
    }

    @Test
    fun `a failed Save can be retried and a later Save goes through`() {
        val request = DanceFigureRequest(name = "Retry Figure", danceTypeId = UUID.randomUUID())
        val id = draftOf(DraftKind.FIGURE, request)
        assertEquals(DraftStatus.PENDING, service.save(id).status)

        // The user fixes the problem (here: the style now exists) by way of the stored payload.
        val fixed = drafts.findById(id).get().apply { payload = codec.toPayload(request.copy(danceTypeId = danceType.id)) }
        drafts.save(fixed)

        val card = service.save(id)

        assertEquals(DraftStatus.SAVED, card.status)
        assertTrue(danceFigures.findAll().any { it.name == "Retry Figure" })
    }

    @Test
    fun `opening a draft in the form discards it, so its card can no longer save`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Opened in the form"))

        assertEquals("/materials/new?fromDraft=$id", service.openInForm(id))

        assertEquals(DraftStatus.DISCARDED, service.view(id).status)
        assertThrows(DraftNotPendingException::class.java) { service.save(id) }
        assertNotNull(service.noteForForm(id), "the form can still read what was discarded")
    }
}
