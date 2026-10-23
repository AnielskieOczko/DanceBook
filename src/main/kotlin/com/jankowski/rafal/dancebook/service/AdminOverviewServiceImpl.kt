package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.AdminOverview
import com.jankowski.rafal.dancebook.dto.AdminUsersView
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AdminOverviewServiceImpl(
    private val appUserRepository: AppUserRepository,
    private val danceFigureRepository: DanceFigureRepository,
    private val materialRepository: MaterialRepository,
    private val storageCleanupLogRepository: StorageCleanupLogRepository,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val trainingCalendarService: TrainingCalendarService,
    private val systemSettingService: SystemSettingService,
    private val knowledgeIndexService: KnowledgeIndexService? = null
) : AdminOverviewService {

    @Transactional(readOnly = true)
    override fun overview(viewer: AppUser?): AdminOverview {
        val calendars = trainingCalendarService.findAll()
        val categories = danceCategoryService.findAll()
        return AdminOverview(
            userCount = appUserRepository.count(),
            figureCount = danceFigureRepository.count(),
            noteCount = materialRepository.count(),
            calendarCount = calendars.size,
            defaultCalendarName = calendars.firstOrNull { it.isDefaultFor(viewer) }?.displayName,
            danceTypeCount = danceTypeService.findAll().size,
            categoryCount = categories.size,
            categoryNames = categories.map { it.name },
            pollInterval = systemSettingService.getIntSetting("polling_interval_minutes", 5),
            autoLogout = systemSettingService.getIntSetting("auto_logout_minutes", 10),
            knowledgeStatus = knowledgeIndexService?.getStatus(),
            knowledgeIndexStale = knowledgeIndexService?.isStale() ?: false,
            lastCleanupAt = lastCleanupAt()
        )
    }

    @Transactional(readOnly = true)
    override fun lastCleanupAt(): LocalDateTime? =
        storageCleanupLogRepository.findAllByOrderByExecutedAtDesc().firstOrNull { !it.isDryRun }?.executedAt

    @Transactional(readOnly = true)
    override fun users(all: Boolean, limit: Int): AdminUsersView {
        val everyone = appUserRepository.findAll().sortedBy { it.createdAt }
        return AdminUsersView(
            users = if (all) everyone else everyone.take(limit),
            total = everyone.size.toLong(),
            adminCount = everyone.count { it.role == Role.ADMIN }.toLong(),
            all = all
        )
    }
}
