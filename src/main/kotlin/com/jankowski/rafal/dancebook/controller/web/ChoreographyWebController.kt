package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.ChoreographyEntryRequest
import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.service.ChoreographyService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Controller
@RequestMapping("/choreographies")
class ChoreographyWebController(
    private val choreographyService: ChoreographyService,
    private val danceTypeService: DanceTypeService,
    private val danceFigureService: DanceFigureService
) {

    companion object {
        private val log = LoggerFactory.getLogger(ChoreographyWebController::class.java)
    }

    @GetMapping
    fun listAll(
        @RequestParam(required = false, defaultValue = "grid") view: String,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model
    ): String {
        log.debug("Listing choreographies in view mode: {}", view)
        val choreographies = choreographyService.findByCurrentUser()
        model.addAttribute("choreographies", choreographies)
        model.addAttribute("currentView", view)
        model.addAttribute("activeNav", "choreographies")

        return if (isHtmxRequest == true) {
            "choreographies/index :: choreographyList"
        } else {
            "choreographies/index"
        }
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        log.debug("Showing choreography create form")
        model.addAttribute("choreographyRequest", ChoreographyRequest())
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("activeNav", "choreographies")
        return "choreographies/form"
    }

    @PostMapping
    fun createChoreography(
        @ModelAttribute("choreographyRequest") request: ChoreographyRequest
    ): String {
        log.debug("Creating choreography with request: {}", request)
        val created = choreographyService.create(request)
        return "redirect:/choreographies/${created.id}/edit"
    }

    @GetMapping("/{id}")
    fun viewChoreography(
        @PathVariable id: UUID,
        model: Model
    ): String {
        log.debug("Viewing choreography: {}", id)
        val choreography = choreographyService.findById(id)
        model.addAttribute("choreography", choreography)
        model.addAttribute("activeNav", "choreographies")
        return "choreographies/view"
    }

    @GetMapping("/{id}/edit")
    fun showBuilderView(
        @PathVariable id: UUID,
        model: Model
    ): String {
        log.debug("Showing builder view for choreography: {}", id)
        val choreography = choreographyService.findById(id)
        model.addAttribute("choreography", choreography)

        // Preload figures for the chosen dance type
        val danceTypeId = choreography.danceType?.id
        val figures = if (danceTypeId != null) {
            danceFigureService.findByDanceType(danceTypeId)
        } else {
            emptyList()
        }
        model.addAttribute("figures", figures)
        model.addAttribute("lineIndicators", com.jankowski.rafal.dancebook.model.LineIndicator.values())
        model.addAttribute("activeNav", "choreographies")

        return "choreographies/edit"
    }

    @GetMapping("/{id}/metadata")
    fun showEditMetadataForm(@PathVariable id: UUID, model: Model): String {
        log.debug("Showing metadata edit form for choreography: {}", id)
        val choreography = choreographyService.findById(id)
        val request = ChoreographyRequest(
            name = choreography.name,
            description = choreography.description,
            danceTypeId = choreography.danceType?.id,
            isPublic = choreography.isPublic
        )
        model.addAttribute("choreographyRequest", request)
        model.addAttribute("choreographyId", id)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("activeNav", "choreographies")
        return "choreographies/form"
    }

    @PostMapping("/{id}/metadata")
    fun updateMetadata(
        @PathVariable id: UUID,
        @ModelAttribute("choreographyRequest") request: ChoreographyRequest
    ): String {
        log.debug("Updating metadata for choreography {}: {}", id, request)
        choreographyService.update(id, request)
        return "redirect:/choreographies/$id"
    }

    @PostMapping("/{id}/delete")
    fun deleteChoreography(@PathVariable id: UUID): String {
        log.debug("Deleting choreography: {}", id)
        choreographyService.delete(id)
        return "redirect:/choreographies"
    }

    @PostMapping("/{id}/duplicate")
    fun duplicateChoreography(@PathVariable id: UUID): String {
        log.debug("Duplicating choreography: {}", id)
        val duplicated = choreographyService.duplicate(id)
        return "redirect:/choreographies/${duplicated.id}/edit"
    }

    // HTMX: Add sequence entry
    @PostMapping("/{id}/entries")
    fun addEntry(
        @PathVariable id: UUID,
        @ModelAttribute request: ChoreographyEntryRequest,
        model: Model
    ): String {
        log.debug("HTMX: Adding entry to choreography {}: {}", id, request)
        val updated = choreographyService.addEntry(id, request)
        model.addAttribute("choreography", updated)
        model.addAttribute("lineIndicators", com.jankowski.rafal.dancebook.model.LineIndicator.values())
        return "choreographies/edit :: sequenceTimeline"
    }

    // HTMX: Remove sequence entry
    @PostMapping("/{id}/entries/{entryId}/delete")
    fun removeEntry(
        @PathVariable id: UUID,
        @PathVariable entryId: UUID,
        model: Model
    ): String {
        log.debug("HTMX: Removing entry {} from choreography {}", entryId, id)
        val updated = choreographyService.removeEntry(id, entryId)
        model.addAttribute("choreography", updated)
        model.addAttribute("lineIndicators", com.jankowski.rafal.dancebook.model.LineIndicator.values())
        return "choreographies/edit :: sequenceTimeline"
    }

    // HTMX: Reorder sequence entries
    @PostMapping("/{id}/entries/reorder")
    fun reorderEntries(
        @PathVariable id: UUID,
        @RequestParam("entryIds") entryIds: List<UUID>,
        model: Model
    ): String {
        log.debug("HTMX: Reordering entries for choreography {}: {}", id, entryIds)
        val updated = choreographyService.reorderEntries(id, entryIds)
        model.addAttribute("choreography", updated)
        model.addAttribute("lineIndicators", com.jankowski.rafal.dancebook.model.LineIndicator.values())
        return "choreographies/edit :: sequenceTimeline"
    }

    // HTMX: Update notes / line indicator
    @PostMapping("/{id}/entries/{entryId}")
    fun updateEntry(
        @PathVariable id: UUID,
        @PathVariable entryId: UUID,
        @ModelAttribute request: ChoreographyEntryRequest,
        model: Model
    ): String {
        log.debug("HTMX: Updating entry {} in choreography {}: {}", entryId, id, request)
        val updated = choreographyService.updateEntry(id, entryId, request)
        model.addAttribute("choreography", updated)
        model.addAttribute("lineIndicators", com.jankowski.rafal.dancebook.model.LineIndicator.values())
        return "choreographies/edit :: sequenceTimeline"
    }

    // HTMX: Figure search within builder
    @GetMapping("/{id}/figures-search")
    fun searchFigures(
        @PathVariable id: UUID,
        @RequestParam("query") query: String,
        model: Model
    ): String {
        log.debug("HTMX: Searching figures in choreography {} for query: {}", id, query)
        val choreography = choreographyService.findById(id)
        val danceTypeId = choreography.danceType?.id

        val figures = if (danceTypeId != null) {
            danceFigureService.findAll(
                typeIds = listOf(danceTypeId),
                categoryIds = null,
                danceClass = null,
                nameSearch = query,
                sortBy = "name_asc"
            )
        } else {
            emptyList()
        }

        model.addAttribute("figures", figures)
        model.addAttribute("choreography", choreography)
        return "choreographies/edit :: figuresList"
    }
}
