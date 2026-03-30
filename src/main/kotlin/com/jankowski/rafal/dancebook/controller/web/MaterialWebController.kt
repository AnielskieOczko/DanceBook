package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.MaterialRequest
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

@Controller
@RequestMapping("/materials")
class MaterialWebController(
    private val materialService: MaterialService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping
    fun listMaterials(
        @RequestParam(required = false) typeId: UUID?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) rating: Short?,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model, 
        pageable: Pageable
    ): String {
        val materialsPage = materialService.findAll(typeId = typeId, categoryId = categoryId, rating = rating, pageable = pageable)
        model.addAttribute("materials", materialsPage.content)
        // Optimisation: We only need to fetch dropdown choices if we are rendering the full page.
        // HTMX requests only swap the table fragment, which doesn't contain the dropdowns!
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
        }
        
        model.addAttribute("selectedTypeId", typeId)
        model.addAttribute("selectedCategoryId", categoryId)
        model.addAttribute("selectedRating", rating)

        return if (isHtmxRequest == true) {
            "materials/list :: materialsTable"
        } else {
            "materials/list"
        }
    }

    @GetMapping("/{id}")
    fun viewMaterial(@PathVariable id: UUID, model: Model): String {
        val material = materialService.findById(id)
        model.addAttribute("material", material)
        return "materials/view"
    }

    @GetMapping("/new")
    fun showCreateForm(model: Model): String {
        model.addAttribute("material", MaterialRequest(name = "", version = 0))
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
            danceTypeId = material.danceType?.id,
            danceCategoryId = material.danceCategory?.id,
            rating = material.rating,
            videoLink = material.videoLink,
            sourceLink = material.sourceLink,
            version = material.version
        )
        model.addAttribute("material", request)
        model.addAttribute("materialId", id)
        populateDropdowns(model)
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

    private fun populateDropdowns(model: Model) {
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceCategories", danceCategoryService.findAll())
    }
}
