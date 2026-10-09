package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import java.util.Optional
import java.util.UUID

class ChoreographyServiceTest {

    private lateinit var choreographyRepository: ChoreographyRepository
    private lateinit var appUserService: AppUserService
    private lateinit var danceTypeService: DanceTypeService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var richTextService: RichTextService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var choreographyService: ChoreographyServiceImpl

    private val currentUser = AppUser().apply { id = UUID.randomUUID(); username = "tester"; role = Role.USER }
    private val danceTypeId = UUID.randomUUID()
    private val danceType = DanceType().apply { id = danceTypeId; name = "Waltz" }

    @BeforeEach
    fun setUp() {
        choreographyRepository = mock(ChoreographyRepository::class.java)
        appUserService = mock(AppUserService::class.java)
        danceTypeService = mock(DanceTypeService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        richTextService = RichTextServiceImpl()
        eventPublisher = mock(ApplicationEventPublisher::class.java)

        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(currentUser)
        `when`(danceTypeService.findById(danceTypeId)).thenReturn(danceType)

        choreographyService = ChoreographyServiceImpl(
            choreographyRepository,
            appUserService,
            danceTypeService,
            danceFigureService,
            richTextService,
            eventPublisher
        )
    }

    @Test
    fun `should create choreography with medal level and with none`() {
        `when`(choreographyRepository.save(any(Choreography::class.java))).thenAnswer { it.arguments[0] as Choreography }

        val withMedal = choreographyService.create(
            ChoreographyRequest(name = "Bronze Choreo", danceTypeId = danceTypeId, medalLevel = MedalLevel.BRONZE)
        )
        val withoutMedal = choreographyService.create(
            ChoreographyRequest(name = "Plain Choreo", danceTypeId = danceTypeId)
        )

        assertEquals(MedalLevel.BRONZE, withMedal.medalLevel)
        assertEquals("Bronze Choreo", withMedal.name)
        assertNull(withoutMedal.medalLevel)
        assertEquals("Plain Choreo", withoutMedal.name)
    }

    @Test
    fun `should set and clear the medal level on update`() {
        val choreoId = UUID.randomUUID()
        val existing = Choreography().apply {
            id = choreoId
            name = "Choreo"
            danceType = this@ChoreographyServiceTest.danceType
            owner = currentUser
            medalLevel = null
        }

        `when`(choreographyRepository.findOne(any<Specification<Choreography>>())).thenReturn(Optional.of(existing))
        `when`(choreographyRepository.save(any(Choreography::class.java))).thenAnswer { it.arguments[0] as Choreography }

        val set = choreographyService.update(
            choreoId,
            ChoreographyRequest(name = "Choreo", danceTypeId = danceTypeId, medalLevel = MedalLevel.GOLD)
        )
        assertEquals(MedalLevel.GOLD, set.medalLevel)

        val cleared = choreographyService.update(
            choreoId,
            ChoreographyRequest(name = "Choreo", danceTypeId = danceTypeId, medalLevel = null)
        )
        assertNull(cleared.medalLevel)
    }

    @Test
    fun `should duplicate choreography preserving medal level`() {
        val choreoId = UUID.randomUUID()
        val original = Choreography().apply {
            id = choreoId
            name = "Original Routine"
            danceType = this@ChoreographyServiceTest.danceType
            owner = currentUser
            medalLevel = MedalLevel.SILVER
        }

        `when`(choreographyRepository.findOne(any<Specification<Choreography>>())).thenReturn(Optional.of(original))
        val captor = ArgumentCaptor.forClass(Choreography::class.java)
        `when`(choreographyRepository.save(captor.capture())).thenAnswer { it.arguments[0] as Choreography }

        val copy = choreographyService.duplicate(choreoId)

        assertEquals("Copy of Original Routine", copy.name)
        assertEquals(MedalLevel.SILVER, copy.medalLevel)
        assertEquals(MedalLevel.SILVER, captor.value.medalLevel)
    }

    @Test
    fun `should query choreographies with medal level filter`() {
        val bronzeChoreo = Choreography().apply {
            id = UUID.randomUUID()
            name = "Bronze Routine"
            medalLevel = MedalLevel.BRONZE
        }
        `when`(choreographyRepository.findAll(any<Specification<Choreography>>(), any(Sort::class.java)))
            .thenReturn(listOf(bronzeChoreo))

        val result = choreographyService.findByCurrentUser(MedalLevel.BRONZE)

        assertEquals(1, result.size)
        assertEquals(MedalLevel.BRONZE, result.first().medalLevel)
        verify(choreographyRepository).findAll(any<Specification<Choreography>>(), any(Sort::class.java))
    }
}
