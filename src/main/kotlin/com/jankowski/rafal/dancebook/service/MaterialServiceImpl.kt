package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.MaterialSpecification
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class MaterialServiceImpl(
    private val materialRepository: MaterialRepository,
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
        rating: Short?
    ): List<Material> {
        log.debug("Retrieving all materials with filters: typeId={}, categoryId={}, rating={}", typeId, categoryId, rating)
        val spec = MaterialSpecification.withFilters(typeId, categoryId, rating)
        return materialRepository.findAll(spec)
    }
}