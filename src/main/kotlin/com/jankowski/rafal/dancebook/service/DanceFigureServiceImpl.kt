package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureSpecification
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DanceFigureServiceImpl(
    private val danceFigureRepository: DanceFigureRepository,
    private val danceTypeService: DanceTypeService,
    private val eventPublisher: ApplicationEventPublisher,
    private val appUserService: AppUserService
) : DanceFigureService {

    companion object {
        private val log = LoggerFactory.getLogger(DanceFigureServiceImpl::class.java)
    }

    override fun findAll(
        typeIds: List<UUID>?,
        categoryIds: List<UUID>?,
        danceClass: DanceClass?,
        nameSearch: String?,
        sortBy: String?
    ): List<DanceFigure> {
        log.debug("Retrieving dance figures with filters: typeIds={}, categoryIds={}, danceClass={}, nameSearch={}, sortBy={}", typeIds, categoryIds, danceClass, nameSearch, sortBy)
        val spec = DanceFigureSpecification.withFilters(
            typeIds = typeIds,
            categoryIds = categoryIds,
            danceClass = danceClass,
            nameSearch = nameSearch
        )

        val sort = when (sortBy) {
            "name_asc" -> Sort.by(Sort.Direction.ASC, "name")
            "name_desc" -> Sort.by(Sort.Direction.DESC, "name")
            "style_asc" -> Sort.by(Sort.Direction.ASC, "danceType.name")
            "style_desc" -> Sort.by(Sort.Direction.DESC, "danceType.name")
            "class_asc" -> Sort.by(Sort.Direction.ASC, "danceClass")
            "class_desc" -> Sort.by(Sort.Direction.DESC, "danceClass")
            else -> Sort.by(Sort.Direction.ASC, "danceType.name").and(Sort.by(Sort.Direction.ASC, "name"))
        }

        return danceFigureRepository.findAll(spec, sort)
    }


    override fun findById(id: UUID): DanceFigure {
        log.debug("Retrieving dance figure for id {}", id)
        return danceFigureRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find dance figure with id $id")
        }
    }

    override fun findByDanceType(danceTypeId: UUID): List<DanceFigure> {
        log.debug("Retrieving dance figures for dance type {}", danceTypeId)
        return danceFigureRepository.findByDanceTypeIdOrderByNameAsc(danceTypeId)
    }

    @Transactional
    override fun create(request: DanceFigureRequest): DanceFigure {
        log.debug("Creating dance figure: {}", request)
        val danceType = danceTypeService.findById(request.danceTypeId!!)
        
        // Check for duplicates
        val existing = findByDanceType(danceType.id!!)
        if (existing.any { it.name.equals(request.name, ignoreCase = true) }) {
            throw IllegalArgumentException("A figure with the name '${request.name}' already exists for this dance.")
        }

        val danceFigure = DanceFigure().apply {
            this.name = request.name
            this.danceType = danceType
            this.danceClass = request.danceClass
            this.predefined = false
            this.alternativeTiming = request.alternativeTiming
        }
        val saved = danceFigureRepository.save(danceFigure)
        eventPublisher.publishEvent(
            DanceFigureCreatedEvent(saved, appUserService.getCurrentUser())
        )
        return saved
     }
 
    @Transactional
    override fun update(id: UUID, request: DanceFigureRequest): DanceFigure {
        log.debug("Updating dance figure {}: {}", id, request)
        val danceFigure = findById(id)
        val danceType = danceTypeService.findById(request.danceTypeId!!)

        // Check for duplicates, excluding ourselves
        val existing = findByDanceType(danceType.id!!)
        if (existing.any { it.id != id && it.name.equals(request.name, ignoreCase = true) }) {
            throw IllegalArgumentException("A figure with the name '${request.name}' already exists for this dance.")
        }

        danceFigure.name = request.name
        danceFigure.danceType = danceType
        danceFigure.danceClass = request.danceClass
        danceFigure.alternativeTiming = request.alternativeTiming

        val saved = danceFigureRepository.save(danceFigure)
        eventPublisher.publishEvent(
            DanceFigureUpdatedEvent(saved, appUserService.getCurrentUser())
        )
        return saved
    }

     @Transactional
     override fun delete(id: UUID) {
         log.debug("Deleting dance figure for id {}", id)
         val existing = findById(id)
         if (existing.predefined) {
             throw IllegalStateException("Cannot delete predefined standard figures.")
         }
         val formattedName = "${existing.danceType?.name ?: ""} - ${existing.name}"
         danceFigureRepository.delete(existing)
         eventPublisher.publishEvent(
             DanceFigureDeletedEvent(id, formattedName, appUserService.getCurrentUser())
         )
     }
 }
