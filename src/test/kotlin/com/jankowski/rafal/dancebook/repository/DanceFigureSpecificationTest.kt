package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** The catalog filter predicates against a real database, as TrainingEventSpecificationTest does. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=figure-spec-test@group.calendar.google.com"])
class DanceFigureSpecificationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var danceFigureRepository: DanceFigureRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    @Test
    fun `filters by medal level independently of the letter class`() {
        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Standard ${UUID.randomUUID()}" })
        val type = danceTypeRepository.save(DanceType().apply { name = "Waltz ${UUID.randomUUID()}"; category = cat })
        fun figure(figureName: String, level: MedalLevel?) = danceFigureRepository.save(DanceFigure().apply {
            name = figureName
            danceType = type
            danceClass = DanceClass.D
            medalLevel = level
        })
        val bronze = figure("Bronze Figure", MedalLevel.BRONZE)
        val gold = figure("Gold Figure", MedalLevel.GOLD)
        val none = figure("Plain Figure", null)

        fun ids(level: MedalLevel?) = danceFigureRepository
            .findAll(DanceFigureSpecification.withFilters(typeIds = listOf(type.id!!), medalLevel = level))
            .mapNotNull { it.id }.toSet()

        assertEquals(setOf(bronze.id, gold.id, none.id), ids(null))
        assertEquals(setOf(bronze.id), ids(MedalLevel.BRONZE))
        assertEquals(setOf(gold.id), ids(MedalLevel.GOLD))
        assertEquals(emptySet<UUID>(), ids(MedalLevel.SILVER))
    }
}
