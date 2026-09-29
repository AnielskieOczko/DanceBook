package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.FigureSuggestionService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
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
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.AppUserService
import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import java.io.IOException
import java.util.UUID

@Controller
@RequestMapping("/materials")
class MaterialWebController(
    private val materialService: MaterialService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val commentService: CommentService,
    private val danceFigureService: DanceFigureService,
    private val appUserService: AppUserService,
    private val noteRewriteService: NoteRewriteService,
    private val figureSuggestionService: FigureSuggestionService
) {

    companion object {
        private val log = LoggerFactory.getLogger(MaterialWebController::class.java)
    }

    @GetMapping
    fun listMaterials(
        @RequestParam(required = false) typeIds: List<UUID>?,
        @RequestParam(required = false) categoryIds: List<UUID>?,
        @RequestParam(required = false) minRating: Short?,
        @RequestParam(required = false) nameSearch: String?,
        @RequestParam(required = false, defaultValue = "list") view: String,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model, 
        @PageableDefault(size = 100, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): String {
        val materialsPage = materialService.findAll(
            typeIds = typeIds,
            categoryIds = categoryIds,
            minRating = minRating,
            nameSearch = nameSearch,
            pageable = pageable
        )
        val materialIds = materialsPage.content.mapNotNull { it.id }
        val figureCounts = if (materialIds.isEmpty()) emptyMap() else materialService.findFigureCounts(materialIds)
        model.addAttribute("materials", materialsPage.content)
        model.addAttribute("figureCounts", figureCounts)
        // Optimisation: We only need to fetch dropdown choices if we are rendering the full page.
        // HTMX requests only swap the table fragment, which doesn't contain the dropdowns!
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
        }
        
        val currentSort = pageable.sort.map { "${it.property},${it.direction.name.lowercase()}" }.firstOrNull() ?: "createdAt,desc"

        model.addAttribute("selectedTypeIds", typeIds ?: emptyList<UUID>())
        model.addAttribute("selectedCategoryIds", categoryIds ?: emptyList<UUID>())
        model.addAttribute("selectedMinRating", minRating)
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("currentView", view)
        model.addAttribute("currentSort", currentSort)

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
        model.addAttribute("material", material)
        model.addAttribute("figures", figures)
        model.addAttribute("comments", comments)
        model.addAttribute("figureRequest", FigureRequest())
        return "materials/view"
    }

    @GetMapping("/{id}/video")
    fun streamVideo(
        @PathVariable id: UUID,
        @RequestHeader(value = "Range", required = false) rangeHeader: String?,
        httpResponse: HttpServletResponse
    ) {
        val media = materialService.downloadVideo(id, rangeHeader)
        httpResponse.status = media.statusCode
        httpResponse.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        media.contentType?.let { httpResponse.contentType = it }
        media.contentLength?.let { httpResponse.setContentLengthLong(it) }
        media.contentRange?.let { httpResponse.setHeader("Content-Range", it) }

        try {
            media.stream.use { input ->
                input.copyTo(httpResponse.outputStream)
            }
        } catch (_: IOException) {
            // Client closed connection / paused / seeked away: harmless for video streaming
        }
    }

    @GetMapping("/{materialId}/figures/picker")
    fun showFigurePicker(
        @PathVariable materialId: UUID,
        model: Model
    ): String {
        val material = materialService.findById(materialId)
        val danceTypeId = material.danceType?.id
        val figures = if (danceTypeId != null) {
            danceFigureService.findAll(
                typeIds = listOf(danceTypeId),
                sortBy = "name_asc"
            )
        } else {
            danceFigureService.findAll(sortBy = "name_asc")
        }
        val pinnedFigureIds = material.figures.mapNotNull { it.danceFigure?.id }.toSet()
        model.addAttribute("material", material)
        model.addAttribute("figures", figures)
        model.addAttribute("pinnedFigureIds", pinnedFigureIds)
        model.addAttribute("allStyles", danceTypeId == null)
        model.addAttribute("query", "")
        model.addAttribute(
            "suggestionsAvailable",
            figureSuggestionService.isAvailable() && !material.description.isNullOrBlank()
        )
        return "materials/fragments/figure-picker :: figurePickerDialog"
    }

    @GetMapping("/{materialId}/figures/search")
    fun searchFigures(
        @PathVariable materialId: UUID,
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "false") allStyles: Boolean,
        model: Model
    ): String {
        val material = materialService.findById(materialId)
        val danceTypeId = if (allStyles) null else material.danceType?.id
        val figures = danceFigureService.findAll(
            typeIds = danceTypeId?.let { listOf(it) },
            nameSearch = query.takeIf { it.isNotBlank() },
            sortBy = "name_asc"
        )
        val pinnedFigureIds = material.figures.mapNotNull { it.danceFigure?.id }.toSet()
        model.addAttribute("material", material)
        model.addAttribute("figures", figures)
        model.addAttribute("pinnedFigureIds", pinnedFigureIds)
        model.addAttribute("allStyles", allStyles)
        model.addAttribute("query", query)
        return "materials/fragments/figure-picker :: figurePickerResults"
    }

    @PostMapping("/{materialId}/figures")
    fun addFigure(
        @PathVariable materialId: UUID,
        @Valid @ModelAttribute("figureRequest") request: FigureRequest,
        bindingResult: BindingResult,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val material = materialService.findById(materialId)
            val figures = materialService.findFiguresByMaterial(materialId)
            val comments = commentService.getCommentsForMaterial(materialId)
            model.addAttribute("material", material)
            model.addAttribute("figures", figures)
            model.addAttribute("comments", comments)
            return "materials/view"
        }
        if (request.id != null) {
            materialService.updateFigure(materialId, request.id, request)
        } else {
            materialService.addFigure(materialId, request)
        }
        if (isHtmxRequest == true) {
            val material = materialService.findById(materialId)
            val figures = materialService.findFiguresByMaterial(materialId)
            model.addAttribute("material", material)
            model.addAttribute("figures", figures)
            return "materials/fragments/figure-picker :: figurePinResponse"
        }
        return "redirect:/materials/$materialId"
    }

    @PostMapping("/{materialId}/figures/{figureId}/delete")
    fun deleteFigure(
        @PathVariable materialId: UUID,
        @PathVariable figureId: UUID,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?,
        model: Model
    ): String {
        materialService.removeFigure(materialId, figureId)
        if (isHtmxRequest == true) {
            val material = materialService.findById(materialId)
            val figures = materialService.findFiguresByMaterial(materialId)
            model.addAttribute("material", material)
            model.addAttribute("figures", figures)
            return "materials/view :: pinnedFiguresSection"
        }
        return "redirect:/materials/$materialId"
    }

    @GetMapping("/{materialId}/figures/suggest")
    fun suggestFigures(
        @PathVariable materialId: UUID,
        model: Model
    ): String {
        val material = materialService.findById(materialId)
        val currentUser = appUserService.getCurrentUser()
        if (material.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw EntityNotFoundException("Could not find material with id $materialId")
        }

        if (!figureSuggestionService.isAvailable()) {
            model.addAttribute("suggestError", "No LLM provider is configured.")
            return "materials/fragments/figure-suggestions :: suggestionsError"
        }

        if (material.description.isNullOrBlank()) {
            model.addAttribute("suggestError", "The note has no text to search for figures.")
            return "materials/fragments/figure-suggestions :: suggestionsError"
        }

        model.addAttribute("material", material)
        return try {
            val suggestions = figureSuggestionService.suggestForMaterial(material)
            model.addAttribute("suggestions", suggestions)
            if (suggestions.isEmpty()) {
                "materials/fragments/figure-suggestions :: suggestionsEmpty"
            } else {
                "materials/fragments/figure-suggestions :: suggestionsPanel"
            }
        } catch (e: Exception) {
            log.warn("Figure suggestion failed for material {}: {}", materialId, e.message, e)
            model.addAttribute("suggestError", "The suggestion service didn't respond. Try again in a moment.")
            model.addAttribute("suggestRetry", true)
            "materials/fragments/figure-suggestions :: suggestionsError"
        }
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
        val currentUser = appUserService.getCurrentUser()
        if (material.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw EntityNotFoundException("Could not find material with id $id")
        }
        val request = MaterialRequest(
            name = material.name,
            description = material.description,
            danceCategoryId = material.danceType?.category?.id,
            danceTypeId = material.danceType?.id,
            rating = material.rating,
            videoLink = material.videoLink,
            sourceLink = material.sourceLink,
            driveFileId = material.driveFileId,
            isPublic = material.isPublic,
            version = material.version
        )
        model.addAttribute("material", request)
        model.addAttribute("materialId", id)
        model.addAttribute("llmAvailable", noteRewriteService.isAvailable() && !material.description.isNullOrBlank())
        populateDropdowns(model)
        val types = request.danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>()
        model.addAttribute("danceTypes", types)
        return "materials/form"
    }

    @PostMapping("/{id}/rewrite")
    fun rewriteWithAi(
        @PathVariable id: UUID,
        @RequestParam(required = false) currentText: String?,
        model: Model
    ): String {
        val material = materialService.findById(id)
        val currentUser = appUserService.getCurrentUser()
        if (material.owner?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw EntityNotFoundException("Could not find material with id $id")
        }

        val text = currentText?.takeIf { it.isNotBlank() }
            ?: material.description

        if (text.isNullOrBlank()) {
            model.addAttribute("rewriteError", "The note has no text to rewrite.")
            return "materials/fragments/ai-rewrite-panel :: rewriteError"
        }

        if (!noteRewriteService.isAvailable()) {
            model.addAttribute("rewriteError", "No LLM provider is configured.")
            return "materials/fragments/ai-rewrite-panel :: rewriteError"
        }

        return try {
            val proposal = noteRewriteService.rewrite(text)
            // Pass the raw stored text as originalHtml so the rich-text fragment's
            // @richTextService.render() handles plain-text-to-HTML conversion correctly.
            // Pass the sanitised proposal HTML as proposalHtml — render() is idempotent
            // on already-sanitised content.
            model.addAttribute("originalHtml", text)
            model.addAttribute("proposalHtml", proposal)
            model.addAttribute("proposalRaw", proposal)
            "materials/fragments/ai-rewrite-panel :: rewritePanel"
        } catch (e: Exception) {
            log.warn("AI rewrite failed for material {}: {}", id, e.message, e)
            model.addAttribute("rewriteError", "The AI rewrite didn't work this time. Your text hasn't changed.")
            "materials/fragments/ai-rewrite-panel :: rewriteError"
        }
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

