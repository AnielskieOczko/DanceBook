package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.FigureRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.MaterialSpecification
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class MaterialServiceImpl(
    private val materialRepository: MaterialRepository,
    private val figureRepository: FigureRepository,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService
) : MaterialService {

    companion object {
        private val log = LoggerFactory.getLogger(MaterialServiceImpl::class.java)
    }

    override fun findById(id: UUID): Material {
        log.debug("Retrieving material for id {}", id)
        return materialRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find material with id $id")
        }
    }

    override fun create(request: MaterialRequest): Material {
        log.debug("Creating material {}", request)
        val material = Material()
        material.name = request.name
        material.description = request.description
        material.rating = request.rating
        material.videoLink = request.videoLink
        material.sourceLink = request.sourceLink
        material.updatedAt = LocalDateTime.now()

        if (material.danceType?.id != request.danceTypeId) {
            material.danceType = request.danceTypeId?.let { danceTypeService.findById(it) }
        }

        if (material.danceCategory?.id != request.danceCategoryId) {
            material.danceCategory = request.danceCategoryId?.let { danceCategoryService.findById(it) }
        }

        return materialRepository.save(material)
    }

    @Transactional
    override fun update(
        id: UUID,
        request: MaterialRequest
    ): Material {
        log.debug("Updating material for id {}", id)
        val existing = findById(id)

        if (existing.version != request.version) {
            throw OptimisticLockException("The material was updated by another user. Please refresh.")
        }

        existing.name = request.name
        existing.description = request.description
        existing.rating = request.rating
        existing.videoLink = request.videoLink
        existing.sourceLink = request.sourceLink
        existing.updatedAt = LocalDateTime.now()

        if (existing.danceType?.id != request.danceTypeId) {
            existing.danceType = request.danceTypeId?.let { danceTypeService.findById(it) }
        }

        if (existing.danceCategory?.id != request.danceCategoryId) {
            existing.danceCategory = request.danceCategoryId?.let { danceCategoryService.findById(it) }
        }

        return materialRepository.save(existing)
    }

    override fun delete(id: UUID) {
        log.debug("Deleting material for id {}", id)
        val existing = findById(id)
        materialRepository.delete(existing)
    }

    override fun findAll(
        typeId: UUID?,
        categoryId: UUID?,
        rating: Short?,
        pageable: Pageable
    ): Page<Material> {
        log.debug("Retrieving all materials with filters: typeId={}, categoryId={}, rating={}", typeId, categoryId, rating)
        val spec = MaterialSpecification.withFilters(typeId, categoryId, rating)
        return materialRepository.findAll(spec, pageable)
    }

    @Transactional
    override fun addFigure(materialId: UUID, request: FigureRequest): Figure {
        log.debug("Adding figure '{}' to material {}", request.name, materialId)
        val material = findById(materialId)
        val figure = Figure().apply {
            name = request.name
            startTime = request.startTime
            endTime = request.endTime
            this.material = material
        }
        material.figures.add(figure)
        materialRepository.save(material)
        return figure
    }

    @Transactional
    override fun removeFigure(materialId: UUID, figureId: UUID) {
        log.debug("Removing figure {} from material {}", figureId, materialId)
        val material = findById(materialId)
        material.figures.removeIf { it.id == figureId }
        materialRepository.save(material)
    }

    override fun findFiguresByMaterial(materialId: UUID): List<Figure> {
        log.debug("Retrieving figures for material {}", materialId)
        return figureRepository.findAllByMaterialIdOrderByStartTimeAsc(materialId)
    }
}