package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

/**
 * The query behind "Export PDF" on the Notes list (#416): who can see which note in an export, and that
 * the parts of a long export are stable and never overlap.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=note-export-spec-test@group.calendar.google.com"])
class MaterialExportSpecificationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
    }

    @Autowired private lateinit var materialRepository: MaterialRepository
    @Autowired private lateinit var danceTypeRepository: DanceTypeRepository
    @Autowired private lateinit var danceCategoryRepository: DanceCategoryRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var calendarClient: GoogleCalendarClient

    private fun user(role: Role = Role.USER) = appUserRepository.save(AppUser().apply {
        username = "u-${UUID.randomUUID()}"
        displayName = "User"
        password = "x"
        this.role = role
    })

    private fun danceType(): DanceType {
        val category = danceCategoryRepository.save(DanceCategory().apply { name = "Category ${UUID.randomUUID()}" })
        return danceTypeRepository.save(DanceType().apply { name = "Type ${UUID.randomUUID()}"; this.category = category })
    }

    private fun note(owner: AppUser, type: DanceType, title: String, public: Boolean = false, at: LocalDateTime? = null) =
        materialRepository.save(Material().apply {
            name = title
            this.owner = owner
            danceType = type
            visibility = if (public) Visibility.PUBLIC else Visibility.PRIVATE
            at?.let { createdAt = it }
        })

    private fun exportIds(viewer: AppUser, typeIds: List<UUID>? = null, search: String? = null): Set<UUID?> =
        materialRepository.findAll(
            MaterialSpecification.visibleForExport(viewer)
                .and(MaterialSpecification.filterCriteria(typeIds, null, null, null, search))
        ).map { it.id }.toSet()

    @Test
    fun `an export never contains another user's private note, even through a filter that matches it`() {
        val type = danceType()
        val alice = user()
        val bob = user()
        val mine = note(alice, type, "Waltz natural turn mine")
        val bobsPrivate = note(bob, type, "Waltz natural turn secret")
        val bobsPublic = note(bob, type, "Waltz natural turn shared", public = true)

        val everything = exportIds(alice)
        assertTrue(mine.id in everything)
        assertTrue(bobsPublic.id in everything)
        assertFalse(bobsPrivate.id in everything)

        // A filter that would match the private note by style and by text does not leak it either.
        val filtered = exportIds(alice, typeIds = listOf(type.id!!), search = "secret")
        assertFalse(bobsPrivate.id in filtered)
        assertTrue(exportIds(alice, typeIds = listOf(type.id!!), search = "waltz").let { mine.id in it && bobsPrivate.id !in it })
    }

    @Test
    fun `an admin export follows the same rule as everyone else`() {
        val type = danceType()
        val admin = user(Role.ADMIN)
        val bob = user()
        val bobsPrivate = note(bob, type, "Foxtrot feather private")
        val bobsPublic = note(bob, type, "Foxtrot feather public", public = true)

        val ids = exportIds(admin, typeIds = listOf(type.id!!))
        assertFalse(bobsPrivate.id in ids)
        assertTrue(bobsPublic.id in ids)
    }

    @Test
    fun `parts of a long export hold every matching note exactly once in a stable order`() {
        val type = danceType()
        val alice = user()
        val sameInstant = LocalDateTime.of(2026, 1, 1, 12, 0)
        // 205 notes with identical timestamps: the id tie-break is what keeps the parts from overlapping.
        repeat(205) { note(alice, type, "Note $it", at = sameInstant) }

        val spec = MaterialSpecification.visibleForExport(alice)
            .and(MaterialSpecification.filterCriteria(listOf(type.id!!), null, null, null, null))
        val sort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"))
        val parts = (0..2).map { materialRepository.findAll(spec, PageRequest.of(it, 100, sort)) }

        assertEquals(listOf(100, 100, 5), parts.map { it.content.size })
        assertEquals(205L, parts.first().totalElements)
        val all = parts.flatMap { p -> p.content.map { it.id } }
        assertEquals(205, all.size)
        assertEquals(205, all.toSet().size, "no note appears in two parts")
    }
}
