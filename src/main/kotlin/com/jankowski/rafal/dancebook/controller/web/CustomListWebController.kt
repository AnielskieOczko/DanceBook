package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.CustomListRequest
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
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

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.service.AppUserService
import jakarta.persistence.EntityNotFoundException

@Controller
@RequestMapping("/lists")
class CustomListWebController(
    private val customListService: CustomListService,
    private val materialService: MaterialService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val appUserService: AppUserService
) {

    @GetMapping
    fun listAll(
        @RequestParam(required = false) typeIds: List<UUID>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false, defaultValue = "list") view: String = "list",
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        model: Model
    ): String {
        val lists = customListService.findVisibleByCurrentUser(
            typeIds = typeIds,
            categoryIds = categoryIds,
            nameSearch = nameSearch,
            sortBy = sortBy
        )
        model.addAttribute("lists", lists)
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
        }
        model.addAttribute("selectedTypeIds", typeIds ?: emptyList<UUID>())
        model.addAttribute("selectedCategoryIds", categoryIds ?: emptyList<UUID>())
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("selectedSortBy", sortBy)
        model.addAttribute("currentView", view)

        return if (isHtmxRequest == true) {
            "lists/index :: collectionsTable"
        } else {
            "lists/index"
        }
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("listRequest", CustomListRequest(name = ""))
        populateDropdowns(model)
        return "lists/form"
    }

    @PostMapping
    fun createList(
        @Valid @ModelAttribute("listRequest") request: CustomListRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            populateDropdowns(model)
            return "lists/form"
        }
        val created = customListService.create(request)
        return "redirect:/lists/${created.id}"
    }

    @GetMapping("/{id}")
    fun viewList(
        @PathVariable id: UUID, 
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "list") view: String,
        @org.springframework.web.bind.annotation.RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model, 
        pageable: Pageable
    ): String {
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

        val currentUser = appUserService.getCurrentUserOrNull()
        val isOwner = currentUser != null && list.owner?.id == currentUser.id
        var privateNotesWarning = false
        if (list.isPublic && isOwner && currentUser != null) {
            privateNotesWarning = materialService.hasPrivateNotesMatchingFilter(
                owner = currentUser,
                typeIds = typeIds.ifEmpty { null },
                categoryIds = categoryIds.ifEmpty { null },
                minRating = list.minRating,
                nameSearch = list.nameFilter
            )
        }

        model.addAttribute("customList", list)
        model.addAttribute("materials", materials.content)
        model.addAttribute("currentView", view)
        model.addAttribute("privateNotesWarning", privateNotesWarning)

        return if (isHtmxRequest == true) {
            "materials/list :: materialsTable"
        } else {
            "lists/view"
        }
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val list = customListService.findById(id)
        val currentUser = appUserService.getCurrentUser()
        if (list.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw EntityNotFoundException("Custom list with id $id not found")
        }
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
        model.addAttribute("currentImage", list.imageFilename)
        populateDropdowns(model)
        return "lists/form"
    }

    @PostMapping("/{id}")
    fun updateList(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("listRequest") request: CustomListRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("listId", id)
            val list = customListService.findById(id)
            model.addAttribute("currentImage", list.imageFilename)
            populateDropdowns(model)
            return "lists/form"
        }
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
