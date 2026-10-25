package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.ActiveFilterChip
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
import org.springframework.data.domain.PageRequest
import org.springframework.web.multipart.MultipartFile

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

        val activeFilterChips = mutableListOf<ActiveFilterChip>()
        if (!categoryIds.isNullOrEmpty()) {
            val allCategories = danceCategoryService.findAll()
            categoryIds.forEach { catId ->
                allCategories.find { it.id == catId }?.let {
                    activeFilterChips.add(ActiveFilterChip("categoryIds", catId.toString(), it.name))
                }
            }
        }
        if (!typeIds.isNullOrEmpty()) {
            val allTypes = danceTypeService.findAll()
            typeIds.forEach { typeId ->
                allTypes.find { it.id == typeId }?.let {
                    activeFilterChips.add(ActiveFilterChip("typeIds", typeId.toString(), it.name))
                }
            }
        }

        val totalCount = lists.size.toLong()
        model.addAttribute("activeFilterChips", activeFilterChips)
        model.addAttribute("activeFilterCount", activeFilterChips.size)
        model.addAttribute("isHtmxRequest", isHtmxRequest == true)
        model.addAttribute("totalCount", totalCount)
        model.addAttribute("shownCount", totalCount)
        model.addAttribute("hasMore", false)
        model.addAttribute("nextPage", 1)

        return if (isHtmxRequest == true) {
            "lists/index :: collectionsTable"
        } else {
            "lists/index"
        }
    }

    companion object {
        const val PREVIEW_LIMIT = 4
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("listRequest", CustomListRequest(name = ""))
        populateDropdowns(model)
        populatePreview(null, null, null, null, model)
        return "lists/form"
    }

    @GetMapping("/preview")
    fun previewRules(
        @RequestParam(required = false) nameFilter: String?,
        @RequestParam(required = false) danceTypeIds: List<UUID>?,
        @RequestParam(required = false) danceCategoryIds: List<UUID>?,
        @RequestParam(required = false) minRating: Short?,
        model: Model
    ): String {
        populatePreview(nameFilter, danceTypeIds, danceCategoryIds, minRating, model)
        return "lists/form :: previewFragments"
    }

    @PostMapping
    fun createList(
        @Valid @ModelAttribute("listRequest") request: CustomListRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        validateImageFile(request.image, bindingResult)
        if (bindingResult.hasErrors()) {
            populateDropdowns(model)
            populatePreview(request.nameFilter, request.danceTypeIds, request.danceCategoryIds, request.minRating, model)
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
        populatePreview(request.nameFilter, request.danceTypeIds, request.danceCategoryIds, request.minRating, model)
        return "lists/form"
    }

    @PostMapping("/{id}")
    fun updateList(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("listRequest") request: CustomListRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        validateImageFile(request.image, bindingResult)
        if (bindingResult.hasErrors()) {
            model.addAttribute("listId", id)
            val list = customListService.findById(id)
            model.addAttribute("currentImage", list.imageFilename)
            populateDropdowns(model)
            populatePreview(request.nameFilter, request.danceTypeIds, request.danceCategoryIds, request.minRating, model)
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

    private fun populatePreview(
        nameFilter: String?,
        danceTypeIds: List<UUID>?,
        danceCategoryIds: List<UUID>?,
        minRating: Short?,
        model: Model
    ) {
        val page = materialService.findAll(
            typeIds = danceTypeIds?.filterNotNull()?.ifEmpty { null },
            categoryIds = danceCategoryIds?.filterNotNull()?.ifEmpty { null },
            minRating = minRating,
            nameSearch = nameFilter?.trim()?.ifEmpty { null },
            pageable = PageRequest.of(0, PREVIEW_LIMIT)
        )
        model.addAttribute("previewNotes", page.content)
        model.addAttribute("previewTotalCount", page.totalElements)
    }

    private fun validateImageFile(file: MultipartFile?, bindingResult: BindingResult) {
        if (file == null || file.isEmpty) return

        if (file.size > 10 * 1024 * 1024) {
            bindingResult.rejectValue("image", "collections.form.image_too_large", "The file is too large. Choose an image under 10 MB.")
            return
        }

        val contentType = file.contentType?.lowercase()
        val filename = file.originalFilename?.lowercase().orEmpty()
        val hasValidExt = filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".gif")
        val hasValidMime = contentType != null && (
            contentType == "image/png" ||
            contentType == "image/jpeg" ||
            contentType == "image/jpg" ||
            contentType == "image/gif"
        )

        if (!hasValidExt && !hasValidMime) {
            bindingResult.rejectValue("image", "collections.form.image_invalid_type", "Only PNG, JPG or GIF images are supported.")
        }
    }
}
