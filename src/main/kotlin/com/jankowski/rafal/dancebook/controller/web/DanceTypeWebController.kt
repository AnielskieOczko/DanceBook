package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceTypeRequest
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import jakarta.validation.Valid
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
@RequestMapping("/dance-types")
class DanceTypeWebController(
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping
    fun listDanceTypes(model: Model): String {
        model.addAttribute("danceTypes", danceTypeService.findAll())
        return "dance-types/list"
    }

    @GetMapping("/new")
    fun showCreateForm(@RequestParam(required = false) categoryId: UUID?, model: Model): String {
        model.addAttribute("danceType", DanceTypeRequest(name = "", categoryId = categoryId))
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        return "dance-types/form"
    }

    @PostMapping
    fun createDanceType(
        @Valid @ModelAttribute("danceType") request: DanceTypeRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            return "dance-types/form"
        }
        danceTypeService.create(request)
        return "redirect:/dance-categories"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val danceType = danceTypeService.findById(id)
        model.addAttribute("danceType", DanceTypeRequest(name = danceType.name, categoryId = danceType.category?.id))
        model.addAttribute("danceTypeId", id)
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        return "dance-types/form"
    }

    @PostMapping("/{id}")
    fun updateDanceType(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("danceType") request: DanceTypeRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("danceTypeId", id)
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            return "dance-types/form"
        }
        danceTypeService.update(id, request)
        return "redirect:/dance-categories"
    }

    @PostMapping("/{id}/delete")
    fun deleteDanceType(@PathVariable id: UUID): String {
        danceTypeService.delete(id)
        return "redirect:/dance-categories"
    }
}
