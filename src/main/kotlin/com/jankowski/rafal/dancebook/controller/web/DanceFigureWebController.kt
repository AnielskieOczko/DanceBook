package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureStepRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureLinkRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import jakarta.validation.Valid
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.util.UUID

@Controller
@RequestMapping("/dance-figures")
class DanceFigureWebController(
    private val danceFigureService: DanceFigureService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping
    fun listDanceFigures(
        @RequestParam(required = false) typeIds: List<UUID>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) danceClass: DanceClass? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false) hasSteps: Boolean? = null,
        @RequestParam(required = false, defaultValue = "grid") view: String = "grid",
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val figures = danceFigureService.findAll(
            typeIds = typeIds,
            categoryIds = categoryIds,
            danceClass = danceClass,
            nameSearch = nameSearch,
            sortBy = sortBy,
            hasSteps = hasSteps
        )

        model.addAttribute("figures", figures)
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
        }
        model.addAttribute("selectedTypeIds", typeIds ?: emptyList<UUID>())
        model.addAttribute("selectedCategoryIds", categoryIds ?: emptyList<UUID>())
        model.addAttribute("selectedDanceClass", danceClass)
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("selectedSortBy", sortBy)
        model.addAttribute("selectedHasSteps", hasSteps)
        model.addAttribute("currentView", view)

        return if (isHtmxRequest == true) {
            "dance-figures/list :: figuresTable"
        } else {
            "dance-figures/list"
        }
    }


    @GetMapping("/new")
    fun showCreateForm(
        @RequestParam(required = false) danceTypeId: UUID?,
        model: Model
    ): String {
        val availableFigures = danceTypeId?.let {
            danceFigureService.findByDanceType(it)
        } ?: emptyList()
        model.addAttribute("danceFigure", DanceFigureRequest(danceTypeId = danceTypeId))
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        return "dance-figures/form"
    }

    @PostMapping
    fun createDanceFigure(
        @Valid @ModelAttribute("danceFigure") request: DanceFigureRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val availableFigures = request.danceTypeId?.let {
                danceFigureService.findByDanceType(it)
            } ?: emptyList()
            model.addAttribute("availableFigures", availableFigures)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        try {
            danceFigureService.create(request)
        } catch (e: Exception) {
            bindingResult.rejectValue("name", "error.danceFigure", e.message ?: "Failed to create figure")
            val availableFigures = request.danceTypeId?.let {
                danceFigureService.findByDanceType(it)
            } ?: emptyList()
            model.addAttribute("availableFigures", availableFigures)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        return "redirect:/dance-figures"
    }

    @GetMapping("/{id}")
    fun showDetails(@PathVariable id: UUID, model: Model): String {
        val danceFigure = danceFigureService.findById(id)
        val styleFigures = danceFigure.danceType?.id?.let {
            danceFigureService.findByDanceType(it)
        } ?: emptyList()
        val figureNameMap = styleFigures.associate { it.name to it.id }

        model.addAttribute("danceFigure", danceFigure)
        model.addAttribute("figureNameMap", figureNameMap)
        return "dance-figures/view"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val danceFigure = danceFigureService.findById(id)
        
        val stepsRequest = danceFigure.steps.map { step ->
            DanceFigureStepRequest(
                id = step.id,
                stepNumber = step.stepNumber,
                timing = step.timing,
                role = step.role,
                foot = step.foot,
                action = step.action,
                footwork = step.footwork,
                alignment = step.alignment,
                amountOfTurn = step.amountOfTurn,
                commentsText = step.comments.sortedBy { it.displayOrder }.joinToString("\n") { it.commentText }
            )
        }.sortedBy { it.stepNumber }.toMutableList()

        val linksRequest = danceFigure.links.map { link ->
            DanceFigureLinkRequest(
                id = link.id,
                url = link.url,
                title = link.title,
                type = link.type
            )
        }.toMutableList()

        val request = DanceFigureRequest(
            name = danceFigure.name,
            danceTypeId = danceFigure.danceType?.id,
            danceClass = danceFigure.danceClass,
            alternativeTiming = danceFigure.alternativeTiming,
            startingFootLeader = danceFigure.startingFootLeader,
            endingFootLeader = danceFigure.endingFootLeader,
            startingFootFollower = danceFigure.startingFootFollower,
            endingFootFollower = danceFigure.endingFootFollower,
            startingPosition = danceFigure.startingPosition,
            endingPosition = danceFigure.endingPosition,
            precedingFigureNames = danceFigure.precedingFigureNames,
            followingFigureNames = danceFigure.followingFigureNames,
            notes = danceFigure.notes,
            steps = stepsRequest,
            links = linksRequest
        )

        val availableFigures = danceFigure.danceType?.id?.let {
            danceFigureService.findByDanceType(it).filter { it.id != id }
        } ?: emptyList()

        model.addAttribute("danceFigure", request)
        model.addAttribute("danceFigureId", id)
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        return "dance-figures/form"
    }

    @PostMapping("/{id}")
    fun updateDanceFigure(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("danceFigure") request: DanceFigureRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val availableFigures = request.danceTypeId?.let {
                danceFigureService.findByDanceType(it).filter { it.id != id }
            } ?: emptyList()
            model.addAttribute("danceFigureId", id)
            model.addAttribute("availableFigures", availableFigures)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        try {
            danceFigureService.update(id, request)
        } catch (e: Exception) {
            bindingResult.rejectValue("name", "error.danceFigure", e.message ?: "Failed to update figure")
            val availableFigures = request.danceTypeId?.let {
                danceFigureService.findByDanceType(it).filter { it.id != id }
            } ?: emptyList()
            model.addAttribute("danceFigureId", id)
            model.addAttribute("availableFigures", availableFigures)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        return "redirect:/dance-figures"
    }

    @PostMapping("/{id}/delete")
    fun deleteDanceFigure(@PathVariable id: UUID): String {
        danceFigureService.delete(id)
        return "redirect:/dance-figures"
    }

    @PostMapping("/inline")
    fun createInline(
        @Valid @ModelAttribute("danceFigure") request: DanceFigureRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        var inlineError: String? = null
        if (bindingResult.hasErrors()) {
            inlineError = bindingResult.allErrors.firstOrNull()?.defaultMessage
        } else {
            try {
                danceFigureService.create(request)
            } catch (e: Exception) {
                inlineError = e.message ?: "Failed to create figure"
            }
        }

        // Fetch updated list of figures for the select dropdown
        val availableFigures = request.danceTypeId?.let {
            danceFigureService.findByDanceType(it)
        } ?: emptyList()

        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("inlineError", inlineError)

        return "materials/view :: figureSelectFragment"
    }

    @GetMapping("/api")
    @ResponseBody
    fun getFiguresForStyle(@RequestParam danceTypeId: UUID): List<Map<String, Any>> {
        return danceFigureService.findByDanceType(danceTypeId).map {
            mapOf("id" to it.id.toString(), "name" to it.name)
        }
    }
}
