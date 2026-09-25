package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.UploadedFile
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.model.MaterialCreatedEvent
import com.jankowski.rafal.dancebook.model.MaterialDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureAddedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureDeletedEvent
import com.jankowski.rafal.dancebook.model.MaterialFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialUpdatedEvent
import com.jankowski.rafal.dancebook.model.MaterialVisibilityChangedEvent
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.MaterialSpecification
import com.jankowski.rafal.dancebook.repository.UploadedFileRepository
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class MaterialServiceImpl(
    private val materialRepository: MaterialRepository,
    private val figureRepository: FigureRepository,
    private val danceTypeService: DanceTypeService,
    private val danceFigureRepository: DanceFigureRepository,
    private val googleDriveService: GoogleDriveService,
    private val uploadedFileRepository: UploadedFileRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val appUserService: AppUserService,
    private val richTextService: RichTextService
) : MaterialService {

    companion object {
        private val log = LoggerFactory.getLogger(MaterialServiceImpl::class.java)
    }

    override fun findById(id: UUID): Material {
        log.debug("Retrieving material for id {}", id)
        val currentUser = appUserService.getCurrentUserOrNull()
        return materialRepository.findOne(
            MaterialSpecification.visibleTo(currentUser).and(MaterialSpecification.byId(id))
        ).orElseThrow {
            EntityNotFoundException("Could not find material with id $id")
        }
    }

    override fun downloadVideo(id: UUID, rangeHeader: String?): GoogleDriveService.DriveMediaDownload {
        log.debug("Streaming video for material id {}", id)
        val material = findById(id)
        val driveFileId = material.driveFileId
            ?: throw EntityNotFoundException("Material with id $id does not have a linked video")
        return googleDriveService.downloadMedia(driveFileId, rangeHeader)
    }

    @Transactional
    override fun create(request: MaterialRequest): Material {
        log.debug("Creating material {}", request)
        val currentUser = appUserService.getCurrentUser()

        if (!request.driveFileId.isNullOrBlank()) {
            verifyUploader(request.driveFileId, currentUser)
        }

        val material = Material()
        material.name = request.name
        material.description = richTextService.clean(request.description)
        material.rating = request.rating
        material.videoLink = request.videoLink
        material.sourceLink = request.sourceLink
        material.driveFileId = request.driveFileId
        material.owner = currentUser
        material.visibility = if (request.isPublic == true) Visibility.PUBLIC else Visibility.PRIVATE
        material.updatedAt = LocalDateTime.now()

        if (material.danceType?.id != request.danceTypeId) {
            material.danceType = request.danceTypeId?.let { danceTypeService.findById(it) }
        }

        return materialRepository.save(material).also {
            eventPublisher.publishEvent(MaterialCreatedEvent(it, currentUser))
        }
    }

    @Transactional
    override fun update(
        id: UUID,
        request: MaterialRequest
    ): Material {
        log.debug("Updating material for id {}", id)
        val currentUser = appUserService.getCurrentUser()
        val existing = findById(id)
        checkOwnership(existing, currentUser)

        if (existing.version != request.version) {
            throw OptimisticLockException("The material was updated by another user. Please refresh.")
        }

        val wasVisibility = existing.visibility
        val targetVisibility = if (request.isPublic != null) (if (request.isPublic) Visibility.PUBLIC else Visibility.PRIVATE) else wasVisibility
        val visibilityChanged = wasVisibility != targetVisibility

        existing.name = request.name
        existing.description = richTextService.clean(request.description)
        existing.rating = request.rating
        existing.videoLink = request.videoLink
        existing.sourceLink = request.sourceLink
        existing.visibility = targetVisibility

        val oldDriveFileId = existing.driveFileId
        val newDriveFileId = if (request.driveFileId.isNullOrBlank()) null else request.driveFileId

        if (newDriveFileId != null && newDriveFileId != oldDriveFileId) {
            verifyUploader(newDriveFileId, currentUser)
        }

        existing.driveFileId = newDriveFileId

        if (oldDriveFileId != null && oldDriveFileId != newDriveFileId) {
            log.info("Drive file ID changed from $oldDriveFileId to $newDriveFileId. Deleting old file from Google Drive.")
            googleDriveService.deleteFile(oldDriveFileId)
            uploadedFileRepository.deleteById(oldDriveFileId)
        }

        existing.updatedAt = LocalDateTime.now()

        if (existing.danceType?.id != request.danceTypeId) {
            existing.danceType = request.danceTypeId?.let { danceTypeService.findById(it) }
        }

        return materialRepository.save(existing).also {
            if (visibilityChanged) {
                eventPublisher.publishEvent(MaterialVisibilityChangedEvent(it, wasVisibility, currentUser))
            }
            eventPublisher.publishEvent(MaterialUpdatedEvent(it, currentUser))
        }
    }

    @Transactional
    override fun delete(id: UUID) {
        log.debug("Deleting material for id {}", id)
        val currentUser = appUserService.getCurrentUser()
        val existing = findById(id)
        checkOwnership(existing, currentUser)

        val driveFileId = existing.driveFileId
        val materialName = existing.name
        val wasPublic = existing.visibility == Visibility.PUBLIC

        materialRepository.delete(existing)

        if (driveFileId != null) {
            log.info("Material {} deleted. Physically deleting associated file {} from Google Drive.", id, driveFileId)
            googleDriveService.deleteFile(driveFileId)
            uploadedFileRepository.deleteById(driveFileId)
        }

        eventPublisher.publishEvent(MaterialDeletedEvent(id, materialName, wasPublic, currentUser))
    }

    override fun findAll(): List<Material> {
        log.debug("Retrieving all materials")
        val currentUser = appUserService.getCurrentUserOrNull()
        return materialRepository.findAll(MaterialSpecification.visibleTo(currentUser), Sort.by(Sort.Direction.ASC, "name"))
    }

    override fun findAll(
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        minRating: Short?,
        nameSearch: String?,
        pageable: Pageable
    ): Page<Material> {
        log.debug("Retrieving all materials with filters: typeIds={}, categoryIds={}, minRating={}, nameSearch={}", typeIds, categoryIds, minRating, nameSearch)
        val currentUser = appUserService.getCurrentUserOrNull()
        val spec = MaterialSpecification.withFilters(currentUser, typeIds, categoryIds, minRating, nameSearch)
        return materialRepository.findAll(spec, pageable)
    }

    @Transactional
    override fun addFigure(materialId: UUID, request: FigureRequest): Figure {
        log.debug("Adding figure '{}' to material {}", request.danceFigureId, materialId)
        val currentUser = appUserService.getCurrentUser()
        val material = findById(materialId)
        checkOwnership(material, currentUser)
        val df = danceFigureRepository.findById(request.danceFigureId!!)
            .orElseThrow { EntityNotFoundException("DanceFigure not found") }
        val figure = Figure().apply {
            startTime = request.startTime
            endTime = request.endTime
            this.material = material
            this.danceFigure = df
        }
        material.figures.add(figure)
        materialRepository.save(material)
        eventPublisher.publishEvent(
            MaterialFigureAddedEvent(material, df.name, currentUser)
        )
        return figure
    }

    @Transactional
    override fun updateFigure(materialId: UUID, figureId: UUID, request: FigureRequest): Figure {
        log.debug("Updating figure {} in material {}", figureId, materialId)
        val currentUser = appUserService.getCurrentUser()
        val material = findById(materialId)
        checkOwnership(material, currentUser)
        val figure = material.figures.find { it.id == figureId }
            ?: throw EntityNotFoundException("Figure not found in material")
        val df = danceFigureRepository.findById(request.danceFigureId!!)
            .orElseThrow { EntityNotFoundException("DanceFigure not found") }
        figure.startTime = request.startTime
        figure.endTime = request.endTime
        figure.danceFigure = df
        materialRepository.save(material)
        eventPublisher.publishEvent(
            MaterialFigureUpdatedEvent(material, df.name, currentUser)
        )
        return figure
    }

    @Transactional
    override fun removeFigure(materialId: UUID, figureId: UUID) {
        log.debug("Removing figure {} from material {}", figureId, materialId)
        val currentUser = appUserService.getCurrentUser()
        val material = findById(materialId)
        checkOwnership(material, currentUser)
        val figure = material.figures.find { it.id == figureId }
            ?: throw EntityNotFoundException("Figure not found in material")
        val figureName = figure.danceFigure?.name ?: "Unknown Figure"
        material.figures.removeIf { it.id == figureId }
        materialRepository.save(material)
        eventPublisher.publishEvent(
            MaterialFigureDeletedEvent(material, figureName, currentUser)
        )
    }

    override fun findFiguresByMaterial(materialId: UUID): List<Figure> {
        log.debug("Retrieving figures for material {}", materialId)
        return figureRepository.findAllByMaterialIdOrderByStartTimeAsc(materialId)
    }

    override fun hasPrivateNotesMatchingFilter(
        owner: AppUser,
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        minRating: Short?,
        nameSearch: String?
    ): Boolean {
        val privateNotesSpec = org.springframework.data.jpa.domain.Specification<Material> { root, _, cb ->
            cb.and(
                cb.equal(root.get<AppUser>("owner"), owner),
                cb.equal(root.get<Visibility>("visibility"), Visibility.PRIVATE)
            )
        }.and(MaterialSpecification.filterCriteria(
            typeIds = typeIds,
            categoryIds = categoryIds,
            minRating = minRating,
            nameSearch = nameSearch
        ))
        return materialRepository.count(privateNotesSpec) > 0
    }

    private fun checkOwnership(material: Material, currentUser: AppUser) {
        if (material.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw AccessDeniedException("You don't have permission to modify this note")
        }
    }

    private fun verifyUploader(driveFileId: String, currentUser: AppUser) {
        val isUploadedByCurrentUser = uploadedFileRepository.existsByDriveFileIdAndUploaderId(driveFileId, currentUser.id!!)
            || googleDriveService.getFileUploaderId(driveFileId) == currentUser.id.toString()

        if (!isUploadedByCurrentUser) {
            throw AccessDeniedException("User does not have permission to attach Drive file $driveFileId")
        }

        if (!uploadedFileRepository.existsById(driveFileId)) {
            uploadedFileRepository.save(
                UploadedFile(
                    driveFileId = driveFileId,
                    uploader = currentUser,
                    createdAt = LocalDateTime.now()
                )
            )
        }
    }
}