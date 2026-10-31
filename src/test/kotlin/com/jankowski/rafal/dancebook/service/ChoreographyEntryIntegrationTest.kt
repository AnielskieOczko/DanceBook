package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.ChoreographyEntryRequest
import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import jakarta.persistence.EntityManagerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class ChoreographyEntryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
    }

    @Autowired private lateinit var choreographyService: ChoreographyService
    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient
    @MockitoBean private lateinit var appUserService: AppUserService

    private lateinit var testUser: AppUser
    private lateinit var testDanceType: DanceType

    private fun <T> inOpenSession(block: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            return block()
        }
        val em = entityManagerFactory.createEntityManager()
        TransactionSynchronizationManager.bindResource(entityManagerFactory, EntityManagerHolder(em))
        try {
            return block()
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory)
            em.close()
        }
    }

    @BeforeEach
    fun setUp() {
        testUser = appUserRepository.save(AppUser().apply {
            username = "choreo-${UUID.randomUUID()}"
            displayName = "Choreo Tester"
            password = "pwd"
            role = Role.USER
        })
        val category = danceCategoryRepository.save(DanceCategory().apply {
            name = "Test Category ${UUID.randomUUID()}"
            predefined = false
        })
        testDanceType = danceTypeRepository.save(DanceType().apply {
            name = "Test Dance Type ${UUID.randomUUID()}"
            predefined = false
            this.category = category
        })

        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)
    }

    @Test
    fun `should remove middle entry without unique constraint violation and maintain order`() {
        val choreo = choreographyService.create(
            ChoreographyRequest(name = "Test Choreo Middle", danceTypeId = testDanceType.id)
        )

        choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "Section A")
        )
        choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "Section B")
        )
        val choreoWith3 = choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "Section C")
        )

        assertEquals(3, choreoWith3.entries.size)
        val entryA = choreoWith3.entries[0]
        val entryB = choreoWith3.entries[1]
        val entryC = choreoWith3.entries[2]

        // Remove the middle entry B
        val afterMiddleRemoval = choreographyService.removeEntry(choreo.id!!, entryB.id!!)

        assertEquals(2, afterMiddleRemoval.entries.size)
        inOpenSession {
            val refreshed = choreographyService.findById(choreo.id!!)
            assertEquals(2, refreshed.entries.size)

            assertEquals(entryA.id, refreshed.entries[0].id)
            assertEquals(0, refreshed.entries[0].sortOrder)
            assertEquals("Section A", refreshed.entries[0].sectionLabel)

            assertEquals(entryC.id, refreshed.entries[1].id)
            assertEquals(1, refreshed.entries[1].sortOrder)
            assertEquals("Section C", refreshed.entries[1].sectionLabel)
        }
    }

    @Test
    fun `should remove start and end entries without unique constraint violation`() {
        val choreo = choreographyService.create(
            ChoreographyRequest(name = "Test Choreo Start End", danceTypeId = testDanceType.id)
        )

        choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "One")
        )
        choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "Two")
        )
        val choreoWith3 = choreographyService.addEntry(
            choreo.id!!,
            ChoreographyEntryRequest(entryType = "SECTION_LABEL", sectionLabel = "Three")
        )

        val entry1 = choreoWith3.entries[0]
        val entry2 = choreoWith3.entries[1]
        val entry3 = choreoWith3.entries[2]

        // Remove first entry
        val afterFirstRemoval = choreographyService.removeEntry(choreo.id!!, entry1.id!!)
        assertEquals(2, afterFirstRemoval.entries.size)
        assertEquals(entry2.id, afterFirstRemoval.entries[0].id)
        assertEquals(0, afterFirstRemoval.entries[0].sortOrder)
        assertEquals(entry3.id, afterFirstRemoval.entries[1].id)
        assertEquals(1, afterFirstRemoval.entries[1].sortOrder)

        // Remove end entry (entry3)
        val afterEndRemoval = choreographyService.removeEntry(choreo.id!!, entry3.id!!)
        assertEquals(1, afterEndRemoval.entries.size)
        assertEquals(entry2.id, afterEndRemoval.entries[0].id)
        assertEquals(0, afterEndRemoval.entries[0].sortOrder)

        // Remove remaining entry
        val afterAllRemoved = choreographyService.removeEntry(choreo.id!!, entry2.id!!)
        assertEquals(0, afterAllRemoved.entries.size)
    }
}
