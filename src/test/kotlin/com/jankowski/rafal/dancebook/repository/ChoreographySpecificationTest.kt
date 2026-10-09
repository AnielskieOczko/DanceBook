package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.*
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

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=choreo-spec-test@group.calendar.google.com"])
class ChoreographySpecificationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var choreographyRepository: ChoreographyRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    @Test
    fun `filters by medal level independently and combined with user visibility`() {
        val cat = danceCategoryRepository.save(DanceCategory().apply { name = "Category ${UUID.randomUUID()}" })
        val type = danceTypeRepository.save(DanceType().apply { name = "Type ${UUID.randomUUID()}"; category = cat })
        val ownerUser = appUserRepository.save(AppUser().apply {
            username = "owner-${UUID.randomUUID()}"
            displayName = "Owner"
            password = "x"
            role = Role.USER
        })
        val otherUser = appUserRepository.save(AppUser().apply {
            username = "other-${UUID.randomUUID()}"
            displayName = "Other"
            password = "x"
            role = Role.USER
        })

        fun choreo(name: String, level: MedalLevel?, public: Boolean = false, owner: AppUser = ownerUser) =
            choreographyRepository.save(Choreography().apply {
                this.name = name
                this.danceType = type
                this.owner = owner
                this.medalLevel = level
                this.visibility = if (public) Visibility.PUBLIC else Visibility.PRIVATE
            })

        val bronzePrivate = choreo("Bronze Private", MedalLevel.BRONZE, public = false)
        val silverPublic = choreo("Silver Public", MedalLevel.SILVER, public = true)
        val goldPrivate = choreo("Gold Private", MedalLevel.GOLD, public = false)
        val nonePublic = choreo("Plain Public", null, public = true)

        fun queryIds(user: AppUser?, level: MedalLevel?) = choreographyRepository
            .findAll(ChoreographySpecification.withFilters(user = user, medalLevel = level))
            .mapNotNull { it.id }
            .toSet()

        // Owner sees all of their private + public matching level
        assertEquals(setOf(bronzePrivate.id), queryIds(ownerUser, MedalLevel.BRONZE))
        assertEquals(setOf(silverPublic.id), queryIds(ownerUser, MedalLevel.SILVER))
        assertEquals(setOf(goldPrivate.id), queryIds(ownerUser, MedalLevel.GOLD))

        // When medalLevel is null, owner sees all four
        val allOwnerIds = queryIds(ownerUser, null)
        assertEquals(true, allOwnerIds.containsAll(setOf(bronzePrivate.id, silverPublic.id, goldPrivate.id, nonePublic.id)))

        // Other user only sees public ones
        assertEquals(emptySet<UUID>(), queryIds(otherUser, MedalLevel.BRONZE))
        assertEquals(setOf(silverPublic.id), queryIds(otherUser, MedalLevel.SILVER))
        assertEquals(emptySet<UUID>(), queryIds(otherUser, MedalLevel.GOLD))
    }
}
