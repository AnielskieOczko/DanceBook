package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.CustomListRequest
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

@Controller
@RequestMapping("/lists")
class CustomListWebController(
    private val customListService: CustomListService,
    private val materialService: MaterialService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("listRequest", CustomListRequest(name = ""))
        populateDropdowns(model)
        return "lists/form"
    }

    @PostMapping
    fun createList(@ModelAttribute("listRequest") request: CustomListRequest): String {
        val created = customListService.create(request)
        return "redirect:/lists/${created.id}"
    }

    @GetMapping("/{id}")
    fun viewList(@PathVariable id: UUID, model: Model, pageable: Pageable): String {
        val list = customListService.findById(id)

        val typeIds = list.danceTypes.mapNotNull { it.id }
        val categoryIds = list.danceCategories.mapNotNull { it.id }

        val materials = materialService.findAll(
            typeIds = typeIds.ifEmpty { null },
            categoryIds = categoryIds.ifEmpty { null },
            minRating = list.minRating,
            nameSearch = list.nameFilter,
            pageable = pageable
        )

        model.addAttribute("customList", list)
        model.addAttribute("materials", materials.content)
        return "lists/view"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val list = customListService.findById(id)
        val request = CustomListRequest(
            name = list.name,
            nameFilter = list.nameFilter,
            danceTypeIds = list.danceTypes.mapNotNull { it.id },
            danceCategoryIds = list.danceCategories.mapNotNull { it.id },
            minRating = list.minRating,
            isPublic = list.isPublic
        )
        model.addAttribute("listRequest", request)
        model.addAttribute("listId", id)
        populateDropdowns(model)
        return "lists/form"
    }

    @PostMapping("/{id}")
    fun updateList(
        @PathVariable id: UUID,
        @ModelAttribute("listRequest") request: CustomListRequest
    ): String {
        customListService.update(id, request)
        return "redirect:/lists/$id"
    }

    @PostMapping("/{id}/delete")
    fun deleteList(@PathVariable id: UUID): String {
        customListService.delete(id)
        return "redirect:/materials"
    }

    private fun populateDropdowns(model: Model) {
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceCategories", danceCategoryService.findAll())
    }
}
