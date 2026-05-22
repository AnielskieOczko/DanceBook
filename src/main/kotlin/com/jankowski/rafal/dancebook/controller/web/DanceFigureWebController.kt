package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
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
import java.util.UUID

@Controller
@RequestMapping("/dance-figures")
class DanceFigureWebController(
    private val danceFigureService: DanceFigureService,
    private val danceTypeService: DanceTypeService
) {

    @GetMapping
    fun listDanceFigures(
        @RequestParam(required = false) danceTypeId: UUID? = null,
        @RequestParam(required = false) danceClass: DanceClass? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false, defaultValue = "grid") view: String = "grid",
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val figures = danceFigureService.findAll(
            danceTypeId = danceTypeId,
            danceClass = danceClass,
            nameSearch = nameSearch,
            sortBy = sortBy
        )

        model.addAttribute("figures", figures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        model.addAttribute("selectedDanceTypeId", danceTypeId)
        model.addAttribute("selectedDanceClass", danceClass)
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("selectedSortBy", sortBy)
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
        model.addAttribute("danceFigure", DanceFigureRequest(danceTypeId = danceTypeId))
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
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        try {
            danceFigureService.create(request)
        } catch (e: Exception) {
            bindingResult.rejectValue("name", "error.danceFigure", e.message ?: "Failed to create figure")
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        return "redirect:/dance-figures"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val danceFigure = danceFigureService.findById(id)
        val request = DanceFigureRequest(
            name = danceFigure.name,
            danceTypeId = danceFigure.danceType?.id,
            danceClass = danceFigure.danceClass,
            alternativeTiming = danceFigure.alternativeTiming
        )
        model.addAttribute("danceFigure", request)
        model.addAttribute("danceFigureId", id)
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
            model.addAttribute("danceFigureId", id)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            return "dance-figures/form"
        }
        try {
            danceFigureService.update(id, request)
        } catch (e: Exception) {
            bindingResult.rejectValue("name", "error.danceFigure", e.message ?: "Failed to update figure")
            model.addAttribute("danceFigureId", id)
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
}
