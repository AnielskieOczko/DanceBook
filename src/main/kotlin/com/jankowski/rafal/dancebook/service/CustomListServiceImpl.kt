package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.CustomListRequest
import com.jankowski.rafal.dancebook.model.CustomList
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.CustomListRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.jankowski.rafal.dancebook.model.ListCreatedEvent
import com.jankowski.rafal.dancebook.model.ListMadePublicEvent
import com.jankowski.rafal.dancebook.repository.CustomListSpecification
import org.springframework.data.domain.Sort

@Service
class CustomListServiceImpl(
    private val customListRepository: CustomListRepository,
    private val appUserService: AppUserService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val fileStorageService: FileStorageService,
    private val eventPublisher: ApplicationEventPublisher
) : CustomListService {

    companion object {
        private val log = LoggerFactory.getLogger(CustomListServiceImpl::class.java)
    }

    override fun findVisibleByCurrentUser(
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        nameSearch: String?,
        sortBy: String?
    ): List<CustomList> {
        val currentUser = appUserService.getCurrentUser()
        log.debug("Retrieving collections for user '{}' with filters: typeIds={}, categoryIds={}, nameSearch={}, sortBy={}", currentUser.username, typeIds, categoryIds, nameSearch, sortBy)
        val spec = CustomListSpecification.withFilters(
            owner = currentUser,
            typeIds = typeIds,
            categoryIds = categoryIds,
            nameSearch = nameSearch
        )

        val sort = when (sortBy) {
            "name_asc" -> Sort.by(Sort.Direction.ASC, "name")
            "name_desc" -> Sort.by(Sort.Direction.DESC, "name")
            "newest" -> Sort.by(Sort.Direction.DESC, "createdAt")
            "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt")
            else -> Sort.by(Sort.Direction.ASC, "name")
        }

        return customListRepository.findAll(spec, sort)
    }

    override fun findById(id: UUID): CustomList {
        return customListRepository.findById(id).orElseThrow {
            EntityNotFoundException("Custom list with id $id not found")
        }
    }

    @Transactional
    override fun create(request: CustomListRequest): CustomList {
        val currentUser = appUserService.getCurrentUser()
        log.debug("User '{}' creating list '{}'", currentUser.username, request.name)

        val list = CustomList().apply {
            name = request.name
            owner = currentUser
            nameFilter = request.nameFilter?.takeIf { it.isNotBlank() }
            minRating = request.minRating
            isPublic = request.isPublic
        }

        request.image?.let { file ->
            if (!file.isEmpty) {
                list.imageFilename = fileStorageService.storeFile(file, "lists")
            }
        }

        applyRelations(list, request)
        return customListRepository.save(list).also {
            eventPublisher.publishEvent(ListCreatedEvent(it, currentUser))
        }
    }

    @Transactional
    override fun update(id: UUID, request: CustomListRequest): CustomList {
        val currentUser = appUserService.getCurrentUser()
        val list = findById(id)

        checkOwnership(list, currentUser)

        log.debug("User '{}' updating list '{}'", currentUser.username, list.name)
        val wasPublicBefore = list.isPublic
        list.name = request.name
        list.nameFilter = request.nameFilter?.takeIf { it.isNotBlank() }
        list.minRating = request.minRating
        list.isPublic = request.isPublic

        request.image?.let { file ->
            if (!file.isEmpty) {
                list.imageFilename?.let { oldFilename ->
                    fileStorageService.deleteFile(oldFilename, "lists")
                }
                list.imageFilename = fileStorageService.storeFile(file, "lists")
            }
        }

        applyRelations(list, request)
        return customListRepository.save(list).also {
            if (!wasPublicBefore && it.isPublic) {
                eventPublisher.publishEvent(ListMadePublicEvent(it, currentUser))
            }
        }
    }

    @Transactional
    override fun delete(id: UUID) {
        val currentUser = appUserService.getCurrentUser()
        val list = findById(id)

        checkOwnership(list, currentUser)

        log.debug("User '{}' deleting list '{}'", currentUser.username, list.name)
        list.imageFilename?.let { filename ->
            fileStorageService.deleteFile(filename, "lists")
        }
        customListRepository.delete(list)
    }

    private fun applyRelations(list: CustomList, request: CustomListRequest) {
        list.danceTypes.clear()
        request.danceTypeIds.forEach { typeId ->
            list.danceTypes.add(danceTypeService.findById(typeId))
        }

        list.danceCategories.clear()
        request.danceCategoryIds.forEach { categoryId ->
            list.danceCategories.add(danceCategoryService.findById(categoryId))
        }
    }

    private fun checkOwnership(list: CustomList, currentUser: com.jankowski.rafal.dancebook.model.AppUser) {
        if (list.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw IllegalStateException("You don't have permission to modify this list")
        }
    }
}
