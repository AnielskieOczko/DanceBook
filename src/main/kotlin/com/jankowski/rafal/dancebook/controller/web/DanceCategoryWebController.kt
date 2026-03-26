package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceCategoryRequest
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import jakarta.validation.Valid
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

@Controller
@RequestMapping("/dance-categories")
class DanceCategoryWebController(
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping
    fun listDanceCategories(model: Model): String {
        model.addAttribute("danceCategories", danceCategoryService.findAll())
        return "dance-categories/list"
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("danceCategory", DanceCategoryRequest(name = ""))
        return "dance-categories/form"
    }

    @PostMapping
    fun createDanceCategory(
        @Valid @ModelAttribute("danceCategory") request: DanceCategoryRequest,
        bindingResult: BindingResult
    ): String {
        if (bindingResult.hasErrors()) {
            return "dance-categories/form"
        }
        danceCategoryService.create(request)
        return "redirect:/dance-categories"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val category = danceCategoryService.findById(id)
        model.addAttribute("danceCategory", DanceCategoryRequest(name = category.name))
        model.addAttribute("danceCategoryId", id)
        return "dance-categories/form"
    }

    @PostMapping("/{id}")
    fun updateDanceCategory(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("danceCategory") request: DanceCategoryRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("danceCategoryId", id)
            return "dance-categories/form"
        }
        danceCategoryService.update(id, request)
        return "redirect:/dance-categories"
    }

    @PostMapping("/{id}/delete")
    fun deleteDanceCategory(@PathVariable id: UUID): String {
        danceCategoryService.delete(id)
        return "redirect:/dance-categories"
    }
}
