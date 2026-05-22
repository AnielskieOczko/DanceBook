package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.DanceFigureService
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

@Controller
@RequestMapping("/materials")
class MaterialWebController(
    private val materialService: MaterialService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val commentService: CommentService,
    private val danceFigureService: DanceFigureService
) {

    @GetMapping
    fun listMaterials(
        @RequestParam(required = false) typeIds: List<UUID>?,
        @RequestParam(required = false) categoryIds: List<UUID>?,
        @RequestParam(required = false) minRating: Short?,
        @RequestParam(required = false) nameSearch: String?,
        @RequestParam(required = false, defaultValue = "grid") view: String,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model, 
        pageable: Pageable
    ): String {
        val materialsPage = materialService.findAll(
            typeIds = typeIds,
            categoryIds = categoryIds,
            minRating = minRating,
            nameSearch = nameSearch,
            pageable = pageable
        )
        model.addAttribute("materials", materialsPage.content)
        // Optimisation: We only need to fetch dropdown choices if we are rendering the full page.
        // HTMX requests only swap the table fragment, which doesn't contain the dropdowns!
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
        }
        
        model.addAttribute("selectedTypeIds", typeIds ?: emptyList<UUID>())
        model.addAttribute("selectedCategoryIds", categoryIds ?: emptyList<UUID>())
        model.addAttribute("selectedMinRating", minRating)
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("currentView", view)

        return if (isHtmxRequest == true) {
            "materials/list :: materialsTable"
        } else {
            "materials/list"
        }
    }

    @GetMapping("/{id}")
    fun viewMaterial(@PathVariable id: UUID, model: Model): String {
        val material = materialService.findById(id)
        val figures = materialService.findFiguresByMaterial(id)
        val comments = commentService.getCommentsForMaterial(id)
        val availableFigures = material.danceType?.id?.let {
            danceFigureService.findByDanceType(it)
        } ?: emptyList()
        model.addAttribute("material", material)
        model.addAttribute("figures", figures)
        model.addAttribute("comments", comments)
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("figureRequest", FigureRequest())
        return "materials/view"
    }

    @PostMapping("/{id}/figures")
    fun addFigure(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("figureRequest") request: FigureRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val material = materialService.findById(id)
            val figures = materialService.findFiguresByMaterial(id)
            val comments = commentService.getCommentsForMaterial(id)
            val availableFigures = material.danceType?.id?.let {
                danceFigureService.findByDanceType(it)
            } ?: emptyList()
            model.addAttribute("material", material)
            model.addAttribute("figures", figures)
            model.addAttribute("comments", comments)
            model.addAttribute("availableFigures", availableFigures)
            return "materials/view"
        }
        if (request.id != null) {
            materialService.updateFigure(id, request.id, request)
        } else {
            materialService.addFigure(id, request)
        }
        return "redirect:/materials/$id"
    }

    @PostMapping("/{materialId}/figures/{figureId}/delete")
    fun deleteFigure(
        @PathVariable materialId: UUID,
        @PathVariable figureId: UUID
    ): String {
        materialService.removeFigure(materialId, figureId)
        return "redirect:/materials/$materialId"
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("material", MaterialRequest(name = "", version = 0))
        model.addAttribute("danceTypes", emptyList<DanceType>())
        populateDropdowns(model)
        return "materials/form"
    }

    @PostMapping
    fun createMaterial(
        @Valid @ModelAttribute("material") request: MaterialRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            populateDropdowns(model)
            val types = request.danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>()
            model.addAttribute("danceTypes", types)
            return "materials/form"
        }
        materialService.create(request)
        return "redirect:/materials"
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val material = materialService.findById(id)
        val request = MaterialRequest(
            name = material.name,
            description = material.description,
            danceCategoryId = material.danceType?.category?.id,
            danceTypeId = material.danceType?.id,
            rating = material.rating,
            videoLink = material.videoLink,
            sourceLink = material.sourceLink,
            driveFileId = material.driveFileId,
            version = material.version
        )
        model.addAttribute("material", request)
        model.addAttribute("materialId", id)
        populateDropdowns(model)
        val types = request.danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>()
        model.addAttribute("danceTypes", types)
        return "materials/form"
    }

    @PostMapping("/{id}")
    fun updateMaterial(
        @PathVariable id: UUID,
        @Valid @ModelAttribute("material") request: MaterialRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            populateDropdowns(model)
            model.addAttribute("materialId", id)
            val types = request.danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>()
            model.addAttribute("danceTypes", types)
            return "materials/form"
        }
        materialService.update(id, request)
        return "redirect:/materials"
    }

    @PostMapping("/{id}/delete")
    fun deleteMaterial(@PathVariable id: UUID): String {
        materialService.delete(id)
        return "redirect:/materials"
    }

    @GetMapping("/dance-types-options")
    fun getDanceTypesOptions(@RequestParam(required = false) danceCategoryId: UUID?, model: Model): String {
        val types = danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList()
        model.addAttribute("danceTypes", types)
        return "materials/form :: danceTypeOptions"
    }

    private fun populateDropdowns(model: Model) {
        model.addAttribute("danceCategories", danceCategoryService.findAll())
    }
}
