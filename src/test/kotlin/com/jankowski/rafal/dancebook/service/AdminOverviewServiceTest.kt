package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.StorageCleanupLog
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDateTime
import java.util.UUID

class AdminOverviewServiceTest {

    private val appUserRepository = mock(AppUserRepository::class.java)
    private val danceFigureRepository = mock(DanceFigureRepository::class.java)
    private val materialRepository = mock(MaterialRepository::class.java)
    private val storageCleanupLogRepository = mock(StorageCleanupLogRepository::class.java)
    private val danceTypeService = mock(DanceTypeService::class.java)
    private val danceCategoryService = mock(DanceCategoryService::class.java)
    private val trainingCalendarService = mock(TrainingCalendarService::class.java)
    private val systemSettingService = mock(SystemSettingService::class.java)
    private val knowledgeIndexService = mock(KnowledgeIndexService::class.java)

    private lateinit var service: AdminOverviewServiceImpl

    @BeforeEach
    fun setUp() {
        service = AdminOverviewServiceImpl(
            appUserRepository, danceFigureRepository, materialRepository, storageCleanupLogRepository,
            danceTypeService, danceCategoryService, trainingCalendarService, systemSettingService, knowledgeIndexService
        )
        `when`(systemSettingService.getIntSetting("polling_interval_minutes", 5)).thenReturn(2)
        `when`(systemSettingService.getIntSetting("auto_logout_minutes", 10)).thenReturn(30)
    }

    private fun user(name: String, role: Role, createdAt: LocalDateTime) = AppUser().apply {
        id = UUID.randomUUID()
        username = name
        this.role = role
        this.createdAt = createdAt
    }

    @Test
    fun `overview counts come from the repositories and the settings from the settings service`() {
        `when`(appUserRepository.count()).thenReturn(12L)
        `when`(danceFigureRepository.count()).thenReturn(374L)
        `when`(materialRepository.count()).thenReturn(43L)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(DanceCategory().apply { name = "Latin" }))
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(emptyList())

        val overview = service.overview(null)

        assertEquals(12L, overview.userCount)
        assertEquals(374L, overview.figureCount)
        assertEquals(43L, overview.noteCount)
        assertEquals(listOf("Latin"), overview.categoryNames)
        assertEquals(2, overview.pollInterval)
        assertEquals(30, overview.autoLogout)
    }

    @Test
    fun `the default calendar is the one the viewer has chosen`() {
        val mine = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Mine" }
        val other = TrainingCalendar().apply { id = UUID.randomUUID(); displayName = "Other" }
        val viewer = user("admin", Role.ADMIN, LocalDateTime.now()).apply { defaultCalendar = mine }
        `when`(trainingCalendarService.findAll()).thenReturn(listOf(other, mine))
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(emptyList())

        val overview = service.overview(viewer)

        assertEquals(2, overview.calendarCount)
        assertEquals("Mine", overview.defaultCalendarName)
    }

    @Test
    fun `the knowledge index needs attention when stale or when chunks lack embeddings, and not otherwise`() {
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(emptyList())

        `when`(knowledgeIndexService.isStale()).thenReturn(true)
        `when`(knowledgeIndexService.getStatus()).thenReturn(KnowledgeIndexStatus(10, 10, true))
        assertTrue(service.overview(null).knowledgeNeedsAttention)

        `when`(knowledgeIndexService.isStale()).thenReturn(false)
        `when`(knowledgeIndexService.getStatus()).thenReturn(KnowledgeIndexStatus(10, 4, false))
        assertTrue(service.overview(null).knowledgeNeedsAttention)

        `when`(knowledgeIndexService.getStatus()).thenReturn(KnowledgeIndexStatus(10, 10, false))
        assertFalse(service.overview(null).knowledgeNeedsAttention)
    }

    @Test
    fun `the last cleanup ignores simulations`() {
        val simulation = StorageCleanupLog(executedAt = LocalDateTime.of(2026, 10, 21, 8, 0), isDryRun = true)
        val deletion = StorageCleanupLog(executedAt = LocalDateTime.of(2026, 10, 20, 8, 0), isDryRun = false)
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(listOf(simulation, deletion))

        assertEquals(LocalDateTime.of(2026, 10, 20, 8, 0), service.lastCleanupAt())
    }

    @Test
    fun `no cleanup has run yet`() {
        `when`(storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()).thenReturn(emptyList())
        assertNull(service.lastCleanupAt())
    }

    @Test
    fun `users shows the first few oldest accounts, and every account when asked for all`() {
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
        val everyone = (1..7).map { user("u$it", if (it == 1) Role.ADMIN else Role.USER, base.plusDays(it.toLong())) }
        `when`(appUserRepository.findAll()).thenReturn(everyone.reversed())

        val preview = service.users(all = false, limit = 5)
        assertEquals(listOf("u1", "u2", "u3", "u4", "u5"), preview.users.map { it.username })
        assertEquals(7L, preview.total)
        assertEquals(1L, preview.adminCount)
        assertFalse(preview.all)

        val full = service.users(all = true, limit = 5)
        assertEquals(7, full.users.size)
        assertTrue(full.all)
    }
}
