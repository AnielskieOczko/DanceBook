package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.ActiveFilterChip
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureStepSetRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureStepRequest
import com.jankowski.rafal.dancebook.dto.DanceFigureLinkRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.MessageSource
import org.springframework.data.domain.PageRequest
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.FigureInUseException
import com.jankowski.rafal.dancebook.service.PdfExportService
import com.jankowski.rafal.dancebook.service.PdfFilterLine
import com.jankowski.rafal.dancebook.service.PdfListCover
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.OptimisticLockException
import jakarta.validation.Valid
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@Controller
@RequestMapping("/dance-figures")
class DanceFigureWebController(
    private val danceFigureService: DanceFigureService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val pdfExportService: com.jankowski.rafal.dancebook.service.PdfExportService? = null,
    private val appUserService: com.jankowski.rafal.dancebook.service.AppUserService? = null,
    private val knowledgeRetrievalService: com.jankowski.rafal.dancebook.service.KnowledgeRetrievalService? = null,
    private val hybridSearchService: com.jankowski.rafal.dancebook.service.HybridSearchService? = null,
    private val semanticSearchFilterHelper: com.jankowski.rafal.dancebook.service.SemanticSearchFilterHelper? = null,
    private val messageSource: MessageSource? = null
) {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(DanceFigureWebController::class.java)
        const val PAGE_SIZE = 24
    }

    /** Present only when the assistant is (#149). The form reads an "Edit in form" draft through it. */
    @Autowired(required = false)
    var assistantDrafts: AssistantDraftService? = null


    @GetMapping
    fun listDanceFigures(
        @RequestParam(required = false) typeIds: List<UUID>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) danceClass: DanceClass? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false) hasSteps: Boolean? = null,
        @RequestParam(required = false) medalLevel: MedalLevel? = null,
        @RequestParam(required = false, defaultValue = "list") view: String = "list",
        @RequestParam(required = false, defaultValue = "0") page: Int = 0,
        @RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean? = null,
        locale: java.util.Locale = java.util.Locale.getDefault(),
        model: Model
    ): String {
        val pageNumber = page.coerceAtLeast(0)
        val pageable = PageRequest.of(pageNumber, PAGE_SIZE)
        val figuresPage = danceFigureService.findAll(
            typeIds = typeIds,
            categoryIds = categoryIds,
            danceClass = danceClass,
            nameSearch = nameSearch,
            sortBy = sortBy,
            hasSteps = hasSteps,
            medalLevel = medalLevel,
            pageable = pageable
        )

        val figures = figuresPage.content

        val candidateFigures = if (pageNumber == 0 && !nameSearch.isNullOrBlank() && hybridSearchService != null) {
            try {
                val singleDanceTypeId = if (typeIds?.size == 1) typeIds.first() else null
                hybridSearchService.search(nameSearch, singleDanceTypeId)
                    .filter { it.type == com.jankowski.rafal.dancebook.service.SearchHitType.FIGURE && it.match == com.jankowski.rafal.dancebook.service.SearchMatch.SEMANTIC }
                    .mapNotNull { it.figure }
            } catch (e: Exception) {
                log.warn("Semantic search unavailable: {}", e.message)
                emptyList()
            }
        } else {
            emptyList()
        }

        val candidateFigureIds = candidateFigures.mapNotNull { it.id }
        val candidateSteps = if (candidateFigureIds.isEmpty()) emptySet() else danceFigureService.findFigureIdsWithSteps(candidateFigureIds)

        val relatedFigures = if (pageNumber == 0 && candidateFigures.isNotEmpty() && semanticSearchFilterHelper != null) {
            semanticSearchFilterHelper.filterAndSortFigures(
                figures = candidateFigures,
                typeIds = typeIds,
                categoryIds = categoryIds,
                danceClass = danceClass,
                medalLevel = medalLevel,
                hasSteps = hasSteps,
                figureIdsWithSteps = candidateSteps,
                sortBy = sortBy,
                excludeIds = figures.mapNotNull { it.id }.toSet()
            )
        } else {
            emptyList()
        }

        val allFigureIds = (figures.mapNotNull { it.id } + relatedFigures.mapNotNull { it.id }).distinct()
        val figuresWithSteps = if (allFigureIds.isEmpty()) emptySet() else danceFigureService.findFigureIdsWithSteps(allFigureIds)

        val totalCount = figuresPage.totalElements
        val shownCount = minOf((figuresPage.number + 1).toLong() * figuresPage.size, totalCount)
        val hasMore = figuresPage.hasNext()
        val nextPage = figuresPage.number + 1

        model.addAttribute("figures", figures)
        model.addAttribute("figuresPage", figuresPage)
        model.addAttribute("totalCount", totalCount)
        model.addAttribute("shownCount", shownCount)
        model.addAttribute("hasMore", hasMore)
        model.addAttribute("nextPage", nextPage)
        model.addAttribute("relatedFigures", relatedFigures)
        model.addAttribute("figuresWithSteps", figuresWithSteps)
        if (isHtmxRequest != true) {
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceCategories", danceCategoryService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            model.addAttribute("medalLevels", MedalLevel.values())
        }
        model.addAttribute("selectedTypeIds", typeIds ?: emptyList<UUID>())
        model.addAttribute("selectedCategoryIds", categoryIds ?: emptyList<UUID>())
        model.addAttribute("selectedDanceClass", danceClass)
        model.addAttribute("selectedMedalLevel", medalLevel)
        model.addAttribute("selectedNameSearch", nameSearch)
        model.addAttribute("selectedSortBy", sortBy)
        model.addAttribute("selectedHasSteps", hasSteps)
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
        if (danceClass != null) {
            val label = messageSource?.getMessage("enum.dance_class.${danceClass.name}", null, "Class ${danceClass.name}", locale)
                ?: "Class ${danceClass.name}"
            activeFilterChips.add(ActiveFilterChip("danceClass", danceClass.name, label))
        }
        if (medalLevel != null) {
            val label = messageSource?.getMessage("figures.medal.${medalLevel.name.lowercase()}", null, medalLevel.displayName, locale)
                ?: medalLevel.displayName
            activeFilterChips.add(ActiveFilterChip("medalLevel", medalLevel.name, label))
        }
        if (hasSteps != null) {
            val key = if (hasSteps) "figures.list.steps.with_steps" else "figures.list.steps.without_steps"
            val fallback = if (hasSteps) "With Steps Only" else "Without Steps Only"
            val label = messageSource?.getMessage(key, null, fallback, locale) ?: fallback
            activeFilterChips.add(ActiveFilterChip("hasSteps", hasSteps.toString(), label))
        }

        model.addAttribute("activeFilterChips", activeFilterChips)
        model.addAttribute("activeFilterCount", activeFilterChips.size)
        model.addAttribute("isHtmxRequest", isHtmxRequest == true)

        return if (isHtmxRequest == true) {
            if (pageNumber > 0) {
                "dance-figures/list :: figuresMore"
            } else {
                "dance-figures/list :: figuresTable"
            }
        } else {
            "dance-figures/list"
        }
    }


    @GetMapping("/new")
    fun showCreateForm(
        @RequestParam(required = false) danceTypeId: UUID?,
        @RequestParam(required = false) fromDraft: UUID?,
        model: Model
    ): String {
        val drafted = fromDraft?.let { assistantDrafts?.figureForForm(it) }
        val form = drafted ?: DanceFigureRequest(danceTypeId = danceTypeId)
        val availableFigures = form.danceTypeId?.let {
            danceFigureService.findByDanceType(it)
        } ?: emptyList()
        model.addAttribute("danceFigure", form)
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        model.addAttribute("medalLevels", MedalLevel.values())
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
            model.addAttribute("medalLevels", MedalLevel.values())
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
            model.addAttribute("medalLevels", MedalLevel.values())
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
        val currentUser = appUserService?.getCurrentUserOrNull()
        val relatedNotes = knowledgeRetrievalService?.findRelatedNotesForFigure(id, currentUser, 3) ?: emptyList()

        model.addAttribute("danceFigure", danceFigure)
        model.addAttribute("figureNameMap", figureNameMap)
        model.addAttribute("relatedNotes", relatedNotes)
        return "dance-figures/view"
    }

    @GetMapping("/{id}/pdf")
    fun exportPdf(@PathVariable id: UUID, locale: Locale): ResponseEntity<ByteArray> {
        val danceFigure = danceFigureService.findById(id)
        val service = pdfExportService ?: throw IllegalStateException("PDF export service not available")
        val pdfBytes = service.exportDanceFigurePdf(danceFigure, locale)
        val filename = sanitizeFilename(danceFigure.name.ifBlank { "figure" }) + ".pdf"

        val contentDisposition = ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes)
    }

    /**
     * Start of "Export PDF" on the Figures list (#416): everything the current filters match. One part goes
     * straight to the download; more than one lists the parts, each a request of its own.
     */
    @GetMapping("/export")
    fun exportOverview(
        @RequestParam(required = false) typeIds: List<UUID>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) danceClass: DanceClass? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false) hasSteps: Boolean? = null,
        @RequestParam(required = false) medalLevel: MedalLevel? = null,
        locale: Locale = Locale.getDefault(),
        model: Model
    ): String {
        val total = danceFigureService.findAll(
            typeIds, categoryIds, danceClass, nameSearch, sortBy, hasSteps, medalLevel, PageRequest.of(0, 1)
        ).totalElements
        val query = exportQuery(typeIds, categoryIds, danceClass, nameSearch, sortBy, hasSteps, medalLevel)
        val parts = ListExportSupport.parts("/dance-figures/export", query, total)
        if (total > 0 && parts.size == 1) {
            return "redirect:" + parts.first().url
        }

        model.addAttribute("exportTitleKey", "export.figures.title")
        model.addAttribute("exportBackKey", "figures.list.title")
        model.addAttribute("exportBackHref", "/dance-figures")
        model.addAttribute("exportFilters", exportFilterLines(typeIds, categoryIds, danceClass, nameSearch, hasSteps, medalLevel, locale))
        model.addAttribute("totalCount", total)
        model.addAttribute("maxPerPdf", PdfExportService.MAX_ITEMS_PER_PDF)
        model.addAttribute("parts", parts)
        return "exports/parts"
    }

    /** One part (`?part=2`, 1-based) of the Figures list export, at most 100 figures in the list's own order. */
    @GetMapping("/export/pdf")
    fun exportListPdf(
        @RequestParam(required = false) typeIds: List<UUID>? = null,
        @RequestParam(required = false) categoryIds: List<UUID>? = null,
        @RequestParam(required = false) danceClass: DanceClass? = null,
        @RequestParam(required = false) nameSearch: String? = null,
        @RequestParam(required = false) sortBy: String? = null,
        @RequestParam(required = false) hasSteps: Boolean? = null,
        @RequestParam(required = false) medalLevel: MedalLevel? = null,
        @RequestParam(required = false, defaultValue = "1") part: Int = 1,
        locale: Locale = Locale.getDefault()
    ): ResponseEntity<ByteArray> {
        if (part < 1) throw EntityNotFoundException("There is no part $part of this export")
        val page = danceFigureService.findAll(
            typeIds, categoryIds, danceClass, nameSearch, sortBy, hasSteps, medalLevel,
            PageRequest.of(part - 1, PdfExportService.MAX_ITEMS_PER_PDF)
        )
        if (page.content.isEmpty()) throw EntityNotFoundException("There is no part $part of this export")

        val totalParts = PdfExportService.partCount(page.totalElements)
        val owner = appUserService?.getCurrentUserOrNull()
        val cover = PdfListCover(
            ownerName = owner?.displayName ?: owner?.username ?: "",
            filters = exportFilterLines(typeIds, categoryIds, danceClass, nameSearch, hasSteps, medalLevel, locale),
            part = part,
            totalParts = totalParts,
            totalItems = page.totalElements
        )
        val service = pdfExportService ?: throw IllegalStateException("PDF export service not available")
        return ListExportSupport.download(service.exportDanceFigureListPdf(page.content, cover, locale), "dancebook-figures", part, totalParts)
    }

    private fun exportQuery(
        typeIds: List<UUID>?, categoryIds: List<UUID>?, danceClass: DanceClass?, nameSearch: String?,
        sortBy: String?, hasSteps: Boolean?, medalLevel: MedalLevel?
    ): List<Pair<String, String>> = buildList {
        typeIds?.forEach { add("typeIds" to it.toString()) }
        categoryIds?.forEach { add("categoryIds" to it.toString()) }
        danceClass?.let { add("danceClass" to it.name) }
        medalLevel?.let { add("medalLevel" to it.name) }
        hasSteps?.let { add("hasSteps" to it.toString()) }
        if (!nameSearch.isNullOrBlank()) add("nameSearch" to nameSearch)
        if (!sortBy.isNullOrBlank()) add("sortBy" to sortBy)
    }

    private fun exportFilterLines(
        typeIds: List<UUID>?, categoryIds: List<UUID>?, danceClass: DanceClass?, nameSearch: String?,
        hasSteps: Boolean?, medalLevel: MedalLevel?, locale: Locale
    ): List<PdfFilterLine> = buildList {
        if (!nameSearch.isNullOrBlank()) add(PdfFilterLine("pdf.list.filter.search", nameSearch.trim()))
        if (!typeIds.isNullOrEmpty()) {
            val names = danceTypeService.findAll().filter { it.id in typeIds }.map { it.name }
            if (names.isNotEmpty()) add(PdfFilterLine("pdf.list.filter.style", names.joinToString(", ")))
        }
        if (!categoryIds.isNullOrEmpty()) {
            val names = danceCategoryService.findAll().filter { it.id in categoryIds }.map { it.name }
            if (names.isNotEmpty()) add(PdfFilterLine("pdf.list.filter.category", names.joinToString(", ")))
        }
        if (danceClass != null) {
            val label = messageSource?.getMessage("enum.dance_class.${danceClass.name}", null, "Class ${danceClass.name}", locale)
                ?: "Class ${danceClass.name}"
            add(PdfFilterLine("pdf.list.filter.class", label))
        }
        if (medalLevel != null) {
            val label = messageSource?.getMessage("figures.medal.${medalLevel.name.lowercase()}", null, medalLevel.displayName, locale)
                ?: medalLevel.displayName
            add(PdfFilterLine("pdf.list.filter.medal", label))
        }
        if (hasSteps != null) {
            val key = if (hasSteps) "figures.list.steps.with_steps" else "figures.list.steps.without_steps"
            val fallback = if (hasSteps) "With Steps Only" else "Without Steps Only"
            add(PdfFilterLine("pdf.list.filter.steps", messageSource?.getMessage(key, null, fallback, locale) ?: fallback))
        }
    }

    @GetMapping("/{id}/edit")
    fun showEditForm(@PathVariable id: UUID, model: Model): String {
        val danceFigure = danceFigureService.findById(id)
        
        val stepSetsRequest = danceFigure.stepSets.map { set ->
            DanceFigureStepSetRequest(
                id = set.id,
                name = set.name,
                isDefault = set.isDefault,
                steps = set.steps.map { step ->
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
            )
        }.toMutableList()

        if (stepSetsRequest.isEmpty()) {
            stepSetsRequest.add(DanceFigureStepSetRequest(name = "Default", isDefault = true))
        }

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
            medalLevel = danceFigure.medalLevel,
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
            steps = mutableListOf(),
            stepSets = stepSetsRequest,
            links = linksRequest,
            version = danceFigure.version
        )

        val availableFigures = danceFigure.danceType?.id?.let {
            danceFigureService.findByDanceType(it).filter { it.id != id }
        } ?: emptyList()

        model.addAttribute("danceFigure", request)
        model.addAttribute("danceFigureId", id)
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        model.addAttribute("medalLevels", MedalLevel.values())
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
            model.addAttribute("medalLevels", MedalLevel.values())
            return "dance-figures/form"
        }
        try {
            danceFigureService.update(id, request)
        } catch (e: Exception) {
            when (e) {
                // Someone else saved first: a form-level error, since no single field is at fault
                is OptimisticLockException, is ObjectOptimisticLockingFailureException ->
                    bindingResult.reject("error.danceFigure.conflict", DanceFigureService.CONFLICT_MESSAGE)
                else -> bindingResult.rejectValue("name", "error.danceFigure", e.message ?: "Failed to update figure")
            }
            val availableFigures = request.danceTypeId?.let {
                danceFigureService.findByDanceType(it).filter { it.id != id }
            } ?: emptyList()
            model.addAttribute("danceFigureId", id)
            model.addAttribute("availableFigures", availableFigures)
            model.addAttribute("danceTypes", danceTypeService.findAll())
            model.addAttribute("danceClasses", DanceClass.values())
            model.addAttribute("medalLevels", MedalLevel.values())
            return "dance-figures/form"
        }
        return "redirect:/dance-figures"
    }

    @PostMapping("/{id}/delete")
    fun deleteDanceFigure(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        try {
            danceFigureService.delete(id)
        } catch (e: FigureInUseException) {
            redirectAttributes.addFlashAttribute("deleteError", e.message)
            return "redirect:/dance-figures/$id"
        }
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

        return "materials/fragments/figure-select :: figureSelectFragment"
    }

    @GetMapping("/api")
    @ResponseBody
    fun getFiguresForStyle(@RequestParam danceTypeId: UUID): List<Map<String, Any>> {
        return danceFigureService.findByDanceType(danceTypeId).map {
            mapOf("id" to it.id.toString(), "name" to it.name)
        }
    }

    private fun sanitizeFilename(input: String): String {
        val sanitized = input.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        return sanitized.ifBlank { "export" }
    }
}
