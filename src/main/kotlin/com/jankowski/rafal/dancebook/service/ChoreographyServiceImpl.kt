package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.ChoreographyEntryRequest
import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.model.*
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class ChoreographyServiceImpl(
    private val choreographyRepository: ChoreographyRepository,
    private val appUserService: AppUserService,
    private val danceTypeService: DanceTypeService,
    private val danceFigureService: DanceFigureService
) : ChoreographyService {

    companion object {
        private val log = LoggerFactory.getLogger(ChoreographyServiceImpl::class.java)
    }

    override fun findByCurrentUser(): List<Choreography> {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Retrieving choreographies for user '{}'", currentUser.username)
        return choreographyRepository.findVisibleByUser(currentUser)
    }

    override fun findById(id: UUID): Choreography {
        log.debug("Retrieving choreography for id {}", id)
        return choreographyRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find choreography with id $id")
        }
    }

    @Transactional
    override fun create(request: ChoreographyRequest): Choreography {
        val currentUser = appUserService.getCurrentUser()
        log.debug("User '{}' creating choreography '{}'", currentUser.username, request.name)

        val danceType = danceTypeService.findById(request.danceTypeId!!)

        val choreography = Choreography().apply {
            name = request.name
            description = request.description?.takeIf { it.isNotBlank() }
            this.danceType = danceType
            owner = currentUser
            isPublic = request.isPublic
            createdAt = LocalDateTime.now()
            updatedAt = LocalDateTime.now()
        }

        return choreographyRepository.save(choreography)
    }

    @Transactional
    override fun update(id: UUID, request: ChoreographyRequest): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(id)
        checkOwnership(choreography, currentUser)

        log.debug("User '{}' updating choreography '{}'", currentUser.username, choreography.name)
        val danceType = danceTypeService.findById(request.danceTypeId!!)

        choreography.name = request.name
        choreography.description = request.description?.takeIf { it.isNotBlank() }
        choreography.danceType = danceType
        choreography.isPublic = request.isPublic
        choreography.updatedAt = LocalDateTime.now()

        return choreographyRepository.save(choreography)
    }

    @Transactional
    override fun delete(id: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(id)
        checkOwnership(choreography, currentUser)

        log.debug("User '{}' deleting choreography '{}'", currentUser.username, choreography.name)
        choreographyRepository.delete(choreography)
    }

    @Transactional
    override fun duplicate(id: UUID): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val original = findById(id)

        // Allow duplication if it is visible to current user (owner or public)
        if (original.owner?.id != currentUser.id && !original.isPublic && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to duplicate this choreography")
        }

        log.debug("User '{}' duplicating choreography '{}'", currentUser.username, original.name)

        val copy = Choreography().apply {
            name = "Copy of ${original.name}"
            description = original.description
            danceType = original.danceType
            owner = currentUser
            isPublic = false // Keep duplicates private by default
            createdAt = LocalDateTime.now()
            updatedAt = LocalDateTime.now()
        }

        original.entries.forEach { entry ->
            val entryCopy = ChoreographyEntry().apply {
                choreography = copy
                entryType = entry.entryType
                danceFigure = entry.danceFigure
                sectionLabel = entry.sectionLabel
                lineIndicator = entry.lineIndicator
                notes = entry.notes
                sortOrder = entry.sortOrder
            }
            copy.entries.add(entryCopy)
        }

        return choreographyRepository.save(copy)
    }

    @Transactional
    override fun addEntry(choreographyId: UUID, request: ChoreographyEntryRequest): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(choreographyId)
        checkOwnership(choreography, currentUser)

        val nextSortOrder = (choreography.entries.maxOfOrNull { it.sortOrder } ?: -1) + 1
        log.debug("Adding entry type '{}' to choreography '{}' at sortOrder {}", request.entryType, choreography.name, nextSortOrder)

        val newEntry = ChoreographyEntry().apply {
            this.choreography = choreography
            this.entryType = EntryType.valueOf(request.entryType)
            this.danceFigure = request.danceFigureId?.let { danceFigureService.findById(it) }
            this.sectionLabel = request.sectionLabel?.takeIf { it.isNotBlank() }
            this.lineIndicator = request.lineIndicator?.takeIf { it.isNotBlank() }?.let { LineIndicator.valueOf(it) }
            this.notes = request.notes?.takeIf { it.isNotBlank() }
            this.sortOrder = nextSortOrder
        }

        choreography.entries.add(newEntry)
        choreography.updatedAt = LocalDateTime.now()

        return choreographyRepository.saveAndFlush(choreography)
    }

    @Transactional
    override fun removeEntry(choreographyId: UUID, entryId: UUID): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(choreographyId)
        checkOwnership(choreography, currentUser)

        log.debug("Removing entry {} from choreography '{}'", entryId, choreography.name)
        val entry = choreography.entries.find { it.id == entryId }
            ?: throw EntityNotFoundException("Entry with id $entryId not found in choreography")

        choreography.entries.remove(entry)

        // Re-index remaining entries
        choreography.entries.sortedBy { it.sortOrder }.forEachIndexed { index, e ->
            e.sortOrder = index
        }
        choreography.updatedAt = LocalDateTime.now()

        return choreographyRepository.saveAndFlush(choreography)
    }

    @Transactional
    override fun reorderEntries(choreographyId: UUID, entryIds: List<UUID>): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(choreographyId)
        checkOwnership(choreography, currentUser)

        log.debug("Reordering entries for choreography '{}': {}", choreography.name, entryIds)
        val entriesMap = choreography.entries.associateBy { it.id }

        // Phase 1: Set temporary out-of-bounds sortOrder to avoid unique constraint violations
        entryIds.forEachIndexed { index, uuid ->
            val entry = entriesMap[uuid]
            if (entry != null) {
                entry.sortOrder = index + 10000
            }
        }
        choreographyRepository.saveAndFlush(choreography)

        // Phase 2: Set final sortOrder
        entryIds.forEachIndexed { index, uuid ->
            val entry = entriesMap[uuid]
            if (entry != null) {
                entry.sortOrder = index
            }
        }

        // Sort in-memory list to match database order
        choreography.entries.sortBy { it.sortOrder }
        choreography.updatedAt = LocalDateTime.now()

        return choreographyRepository.saveAndFlush(choreography)
    }

    @Transactional
    override fun updateEntry(choreographyId: UUID, entryId: UUID, request: ChoreographyEntryRequest): Choreography {
        val currentUser = appUserService.getCurrentUser()
        val choreography = findById(choreographyId)
        checkOwnership(choreography, currentUser)

        log.debug("Updating entry {} in choreography '{}'", entryId, choreography.name)
        val entry = choreography.entries.find { it.id == entryId }
            ?: throw EntityNotFoundException("Entry with id $entryId not found in choreography")

        entry.notes = request.notes?.takeIf { it.isNotBlank() }
        entry.lineIndicator = request.lineIndicator?.takeIf { it.isNotBlank() }?.let { LineIndicator.valueOf(it) }
        
        if (entry.entryType == EntryType.SECTION_LABEL) {
            entry.sectionLabel = request.sectionLabel?.takeIf { it.isNotBlank() }
        }

        choreography.updatedAt = LocalDateTime.now()
        return choreographyRepository.saveAndFlush(choreography)
    }

    private fun checkOwnership(choreography: Choreography, currentUser: AppUser) {
        if (choreography.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to modify this choreography")
        }
    }
}
