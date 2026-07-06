package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureStepRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureVariationRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureLink
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.DanceFigureStepComment
import com.jankowski.rafal.dancebook.model.DanceFigureVariation
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DanceFigureCreatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureUpdatedEvent
import com.jankowski.rafal.dancebook.model.DanceFigureDeletedEvent
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureVariationRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureStepRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureSpecification
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DanceFigureServiceImpl(
    private val danceFigureRepository: DanceFigureRepository,
    private val danceFigureVariationRepository: DanceFigureVariationRepository,
    private val danceFigureStepRepository: DanceFigureStepRepository,
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
        sortBy: String?,
        hasSteps: Boolean?
    ): List<DanceFigure> {
        log.debug("Retrieving dance figures with filters: typeIds={}, categoryIds={}, danceClass={}, nameSearch={}, sortBy={}, hasSteps={}", typeIds, categoryIds, danceClass, nameSearch, sortBy, hasSteps)
        val spec = DanceFigureSpecification.withFilters(
            typeIds = typeIds,
            categoryIds = categoryIds,
            danceClass = danceClass,
            nameSearch = nameSearch,
            hasSteps = hasSteps
        )
        val list = danceFigureRepository.findAll(spec)
        return when (sortBy) {
            "name" -> list.sortedBy { it.name }
            "danceType" -> list.sortedBy { it.danceType?.name ?: "" }
            "level" -> list.sortedBy { it.danceClass?.ordinal ?: Int.MAX_VALUE }
            else -> list.sortedBy { it.name }
        }
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
            this.predefined = false
        }
        mapRequestToEntity(danceFigure, request, danceType)

        // Create default standard variation
        val defaultVariation = DanceFigureVariation().apply {
            this.danceFigure = danceFigure
            this.name = "Standard"
            this.isDefault = true
            this.timing = request.steps.filter { it.role == "LEADER" }.joinToString("") { it.timing }.ifBlank { "Standard" }
            this.startingFootLeader = request.startingFootLeader
            this.endingFootLeader = request.endingFootLeader
            this.startingFootFollower = request.startingFootFollower
            this.endingFootFollower = request.endingFootFollower
            this.startingPosition = request.startingPosition
            this.endingPosition = request.endingPosition
        }
        mapVariationSteps(defaultVariation, request.steps)
        danceFigure.variations.add(defaultVariation)

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

        mapRequestToEntity(danceFigure, request, danceType)

        val saved = danceFigureRepository.save(danceFigure)
        eventPublisher.publishEvent(
            DanceFigureUpdatedEvent(saved, appUserService.getCurrentUser())
        )
        return saved
    }

    private fun mapRequestToEntity(danceFigure: DanceFigure, request: DanceFigureRequest, danceType: DanceType) {
        danceFigure.name = request.name
        danceFigure.danceType = danceType
        danceFigure.danceClass = request.danceClass
        danceFigure.precedingFigureNames = request.precedingFigureNames
        danceFigure.followingFigureNames = request.followingFigureNames
        danceFigure.notes = request.notes

        // Links
        danceFigure.links.clear()
        request.links.forEach { linkReq ->
            val link = DanceFigureLink().apply {
                this.danceFigure = danceFigure
                this.url = linkReq.url
                this.title = linkReq.title
                this.type = linkReq.type
            }
            danceFigure.links.add(link)
        }
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

    override fun findVariationById(variationId: UUID): DanceFigureVariation {
        log.debug("Retrieving dance figure variation for id {}", variationId)
        return danceFigureVariationRepository.findById(variationId).orElseThrow {
            EntityNotFoundException("Could not find dance figure variation with id $variationId")
        }
    }

    @Transactional
    override fun createVariation(figureId: UUID, request: DanceFigureVariationRequest): DanceFigureVariation {
        log.debug("Creating variation for figure {}: {}", figureId, request)
        val figure = findById(figureId)

        // If this variation is default, unset other defaults
        if (request.isDefault) {
            figure.variations.forEach { it.isDefault = false }
        }

        val variation = DanceFigureVariation().apply {
            this.danceFigure = figure
            this.name = request.name
            this.timing = request.timing
            this.isDefault = request.isDefault
            this.startingFootLeader = request.startingFootLeader
            this.endingFootLeader = request.endingFootLeader
            this.startingFootFollower = request.startingFootFollower
            this.endingFootFollower = request.endingFootFollower
            this.startingPosition = request.startingPosition
            this.endingPosition = request.endingPosition
        }

        mapVariationSteps(variation, request.steps)
        figure.variations.add(variation)
        danceFigureRepository.save(figure)
        return variation
    }

    @Transactional
    override fun updateVariation(variationId: UUID, request: DanceFigureVariationRequest): DanceFigureVariation {
        log.debug("Updating variation {}: {}", variationId, request)
        val variation = findVariationById(variationId)
        val figure = variation.danceFigure!!

        // If this variation is default, unset other defaults
        if (request.isDefault && !variation.isDefault) {
            figure.variations.forEach { it.isDefault = false }
        }

        variation.name = request.name
        variation.timing = request.timing
        variation.isDefault = request.isDefault
        variation.startingFootLeader = request.startingFootLeader
        variation.endingFootLeader = request.endingFootLeader
        variation.startingFootFollower = request.startingFootFollower
        variation.endingFootFollower = request.endingFootFollower
        variation.startingPosition = request.startingPosition
        variation.endingPosition = request.endingPosition

        variation.steps.clear()
        mapVariationSteps(variation, request.steps)

        danceFigureVariationRepository.save(variation)
        return variation
    }

    @Transactional
    override fun deleteVariation(variationId: UUID) {
        log.debug("Deleting variation {}", variationId)
        val variation = findVariationById(variationId)
        val figure = variation.danceFigure!!

        if (variation.isDefault) {
            throw IllegalArgumentException("Cannot delete the default variation of a figure. Please mark another variation as default first.")
        }

        figure.variations.remove(variation)
        danceFigureRepository.save(figure)
    }

    private fun mapVariationSteps(variation: DanceFigureVariation, stepRequests: List<DanceFigureStepRequest>) {
        val leaderSteps = stepRequests.filter { it.role == "LEADER" }
        val followerSteps = stepRequests.filter { it.role == "FOLLOWER" }

        leaderSteps.forEachIndexed { index, stepReq ->
            val step = DanceFigureStep().apply {
                this.danceFigureVariation = variation
                this.stepNumber = index + 1
                this.timing = stepReq.timing
                this.role = "LEADER"
                this.foot = stepReq.foot
                this.action = stepReq.action
                this.footwork = stepReq.footwork
                this.alignment = stepReq.alignment
                this.amountOfTurn = stepReq.amountOfTurn
            }
            val comments = stepReq.commentsText?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapIndexed { commentIndex, commentText ->
                    DanceFigureStepComment().apply {
                        this.danceFigureStep = step
                        this.commentText = commentText
                        this.displayOrder = commentIndex
                    }
                }?.toMutableList() ?: mutableListOf()
            step.comments = comments
            variation.steps.add(step)
        }

        followerSteps.forEachIndexed { index, stepReq ->
            val step = DanceFigureStep().apply {
                this.danceFigureVariation = variation
                this.stepNumber = index + 1
                this.timing = stepReq.timing
                this.role = "FOLLOWER"
                this.foot = stepReq.foot
                this.action = stepReq.action
                this.footwork = stepReq.footwork
                this.alignment = stepReq.alignment
                this.amountOfTurn = stepReq.amountOfTurn
            }
            val comments = stepReq.commentsText?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapIndexed { commentIndex, commentText ->
                    DanceFigureStepComment().apply {
                        this.danceFigureStep = step
                        this.commentText = commentText
                        this.displayOrder = commentIndex
                    }
                }?.toMutableList() ?: mutableListOf()
            step.comments = comments
            variation.steps.add(step)
        }
    }
}
