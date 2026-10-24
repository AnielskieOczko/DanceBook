package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.FormSelectOption
import com.jankowski.rafal.dancebook.dto.UserCreateRequest
import com.jankowski.rafal.dancebook.dto.UserUpdateRequest
import com.jankowski.rafal.dancebook.model.MedalBadgeStyle
import com.jankowski.rafal.dancebook.service.AdminOverviewService
import com.jankowski.rafal.dancebook.service.AdminStorageService
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.jankowski.rafal.dancebook.service.StorageCleanupJob
import com.jankowski.rafal.dancebook.service.SyllabusImporterService
import com.jankowski.rafal.dancebook.service.SystemSettingService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
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
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')") // Only you can enter
class AdminController(
    private val appUserService: com.jankowski.rafal.dancebook.service.AppUserService,
    private val adminOverviewService: AdminOverviewService,
    private val adminStorageService: AdminStorageService,
    private val storageCleanupJob: StorageCleanupJob,
    private val googleDriveService: GoogleDriveService,
    private val fileStorageService: com.jankowski.rafal.dancebook.service.FileStorageService,
    private val systemSettingService: SystemSettingService,
    private val syllabusImporterService: SyllabusImporterService,
    private val knowledgeIndexService: com.jankowski.rafal.dancebook.service.KnowledgeIndexService? = null,
    private val messageSource: org.springframework.context.MessageSource? = null
) {
    @GetMapping
    fun dashboard(model: Model): String {
        val overview = adminOverviewService.overview(appUserService.getCurrentUser())
        model.addAttribute("overview", overview)
        model.addAttribute("isKnowledgeIndexStale", overview.knowledgeIndexStale)
        model.addAttribute("knowledgeStatus", overview.knowledgeStatus)
        addSystemSettings(model)
        addUsers(model, all = false)
        model.addAttribute("cleanupLogs", adminStorageService.cleanupHistory(AdminStorageService.PREVIEW_ROWS))
        model.addAttribute("cleanupLogsPreview", true)
        return "admin/dashboard"
    }

    /** The full list of users, for when the dashboard row's first few are not enough. */
    @GetMapping("/users")
    fun usersPage(model: Model): String {
        addUsers(model, all = true)
        return "admin/users"
    }

    /** Every Drive file and every cleanup run, for when the dashboard row's first few are not enough. */
    @GetMapping("/storage")
    fun storagePage(model: Model): String {
        model.addAttribute("cleanupLogs", adminStorageService.cleanupHistory())
        return "admin/storage"
    }

    private fun addUsers(model: Model, all: Boolean) {
        val view = adminOverviewService.users(all)
        model.addAttribute("users", view.users)
        model.addAttribute("usersTotal", view.total)
        model.addAttribute("usersAdmins", view.adminCount)
        model.addAttribute("usersAll", view.all)
    }

    private fun addSystemSettings(model: Model) {
        model.addAttribute("pollInterval", systemSettingService.getIntSetting("polling_interval_minutes", 5))
        model.addAttribute("autoLogout", systemSettingService.getIntSetting("auto_logout_minutes", 10))
        model.addAttribute("medalBadgeStyle", systemSettingService.getSetting("medal_badge_style", "rank_bars"))
        val locale = org.springframework.context.i18n.LocaleContextHolder.getLocale()
        val badgeStyleOptions = MedalBadgeStyle.entries.map {
            FormSelectOption(
                value = it.code,
                label = messageSource?.getMessage("figures.medal.style.${it.code}", null, it.displayName, locale) ?: it.displayName
            )
        }
        model.addAttribute("badgeStyleOptions", badgeStyleOptions)
    }

    @PostMapping("/settings/default-list-image")
    fun uploadDefaultListImage(
        @RequestParam("image") file: org.springframework.web.multipart.MultipartFile,
        redirectAttributes: org.springframework.web.servlet.mvc.support.RedirectAttributes
    ): String {
        try {
            if (!file.isEmpty) {
                fileStorageService.storeFileWithFilename(file, "defaults", "list-placeholder.png")
                redirectAttributes.addFlashAttribute("settingsSuccess", "Default List image updated successfully!")
            }
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("settingsError", "Failed to upload image: ${e.message}")
        }
        return "redirect:/admin"
    }

    @PostMapping("/settings/default-category-image")
    fun uploadDefaultCategoryImage(
        @RequestParam("image") file: org.springframework.web.multipart.MultipartFile,
        redirectAttributes: org.springframework.web.servlet.mvc.support.RedirectAttributes
    ): String {
        try {
            if (!file.isEmpty) {
                fileStorageService.storeFileWithFilename(file, "defaults", "category-placeholder.png")
                redirectAttributes.addFlashAttribute("settingsSuccess", "Default Category image updated successfully!")
            }
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("settingsError", "Failed to upload image: ${e.message}")
        }
        return "redirect:/admin"
    }

    @PostMapping("/settings/system")
    fun updateSystemSettings(
        @RequestParam("pollInterval") pollInterval: Int,
        @RequestParam("autoLogout") autoLogout: Int,
        @RequestParam(value = "medalBadgeStyle", required = false) medalBadgeStyle: String?,
        model: Model
    ): String {
        try {
            systemSettingService.updateSetting("polling_interval_minutes", pollInterval.toString())
            systemSettingService.updateSetting("auto_logout_minutes", autoLogout.toString())
            if (!medalBadgeStyle.isNullOrBlank()) {
                val valid = MedalBadgeStyle.fromStringOrNull(medalBadgeStyle)
                if (valid != null) {
                    systemSettingService.updateSetting("medal_badge_style", valid.code)
                }
            }
            model.addAttribute("systemSettingsSuccess", "System settings updated successfully!")
        } catch (e: Exception) {
            model.addAttribute("systemSettingsError", "Failed to update settings: ${e.message}")
        }
        addSystemSettings(model)
        model.addAttribute("systemSettingsOob", true)
        return "admin/dashboard :: systemSettingsSection"
    }

    // HTMX Endpoint for Lazy Loading Heavy Google Drive Files. preview=true is the dashboard row's first few.
    @GetMapping("/storage/files")
    fun fetchDriveFiles(
        @RequestParam(value = "preview", defaultValue = "false") preview: Boolean,
        model: Model
    ): String {
        val inspection = adminStorageService.inspectDrive()

        model.addAttribute("driveFiles", if (preview) inspection.files.take(AdminStorageService.PREVIEW_ROWS) else inspection.files)
        model.addAttribute("drivePreview", preview)
        model.addAttribute("orphanedSize", inspection.orphanedSize)
        model.addAttribute("linkedSize", inspection.linkedSize)
        model.addAttribute("totalSize", inspection.totalSize)

        model.addAttribute("orphanedCount", inspection.orphanedCount)
        model.addAttribute("linkedCount", inspection.linkedCount)
        model.addAttribute("totalCount", inspection.totalCount)

        return "admin/dashboard :: driveSection"
    }

    // HTMX Endpoint for the Google Drive row's value and its "Needs attention" chip. Drive is slow and can be
    // unreachable, so the dashboard asks for this after it has rendered rather than waiting on Google.
    @GetMapping("/storage/summary")
    fun driveSummary(model: Model): String {
        val inspection = try {
            adminStorageService.inspectDrive()
        } catch (e: Exception) {
            null
        }
        model.addAttribute("driveInspection", inspection)
        model.addAttribute("lastCleanupAt", adminOverviewService.lastCleanupAt())
        return "admin/dashboard :: driveSummary"
    }

    // HTMX Endpoint for Simulation
    @PostMapping("/storage/simulate")
    fun simulateCleanup(model: Model): String {
        val orphans = storageCleanupJob.runCleanup(dryRun = true)
        model.addAttribute("orphanedFiles", orphans)
        return "admin/dashboard :: cleanupResults"
        // This syntax means "Only return the HTML block named 'cleanupResults', not the whole page"
    }
    // HTMX Endpoint for Actual Deletion
    @PostMapping("/storage/execute")
    fun executeCleanup(model: Model): String {
        val deleted = storageCleanupJob.runCleanup(dryRun = false)
        model.addAttribute("deletedCount", deleted.size)
        return "admin/dashboard :: cleanupSuccess"
    }

    // HTMX Endpoint for Permissions Cleanup
    @PostMapping("/storage/clean-permissions")
    fun cleanPermissions(model: Model): String {
        val result = googleDriveService.cleanPermissions()
        model.addAttribute("permissionResult", result)
        return "admin/dashboard :: permissionCleanupSuccess"
    }

    @GetMapping("/users/form")
    fun showCreateUserForm(@RequestParam(value = "all", defaultValue = "false") all: Boolean, model: Model): String {
        model.addAttribute("usersAll", all)
        return "admin/dashboard :: createUserForm"
    }

    @GetMapping("/users/form/cancel")
    fun cancelCreateUserForm(): String {
        return "admin/dashboard :: empty"
    }

    @PostMapping("/users")
    fun createUser(
        @Valid @ModelAttribute request: UserCreateRequest,
        bindingResult: BindingResult,
        @RequestParam(value = "all", defaultValue = "false") all: Boolean,
        model: Model
    ): String {
        if (bindingResult.hasErrors()) {
            val errorMsg = bindingResult.allErrors.firstOrNull()?.let {
                messageSource?.getMessage(it, org.springframework.context.i18n.LocaleContextHolder.getLocale()) ?: it.defaultMessage
            } ?: "Validation failed"
            model.addAttribute("createUserError", errorMsg)
            model.addAttribute("showCreateForm", true)
            addUsers(model, all)
            return "admin/dashboard :: usersSection"
        }
        try {
            appUserService.createUser(request)
            model.addAttribute("createUserSuccess", "Account created successfully!")
        } catch (e: Exception) {
            model.addAttribute("createUserError", e.message)
            model.addAttribute("showCreateForm", true)
        }
        addUsers(model, all)
        model.addAttribute("usersOob", true)
        return "admin/dashboard :: usersSection"
    }

    @GetMapping("/users/{id}/edit")
    fun getDataForEditUser(@PathVariable id: String, model: Model): String {
        val user = appUserService.findById(UUID.fromString(id))
        model.addAttribute("editUser", user)
        return "admin/dashboard :: editUserRow"
    }

    @GetMapping("/users/{id}")
    fun cancelEditUser(@PathVariable id: String, model: Model): String {
        val user = appUserService.findById(UUID.fromString(id))
        model.addAttribute("user", user)
        return "admin/dashboard :: userRow"
    }

    @PostMapping("/users/{id}/edit")
    fun editUser(
        @PathVariable id: String,
        @Valid @ModelAttribute request: UserUpdateRequest,
        bindingResult: BindingResult,
        model: Model
    ): String {
        val userId = UUID.fromString(id)
        if (bindingResult.hasErrors()) {
            val errorMsg = bindingResult.allErrors.firstOrNull()?.let {
                messageSource?.getMessage(it, org.springframework.context.i18n.LocaleContextHolder.getLocale()) ?: it.defaultMessage
            } ?: "Validation failed"
            model.addAttribute("updateUserError", errorMsg)
            val user = appUserService.findById(userId)
            user.username = request.username
            user.email = request.email
            user.displayName = request.displayName
            request.role?.let { user.role = it }
            model.addAttribute("editUser", user)
            return "admin/dashboard :: editUserRow"
        }
        try {
            val updatedUser = appUserService.updateUser(userId, request)
            model.addAttribute("user", updatedUser)
            return "admin/dashboard :: userRow"
        } catch (e: Exception) {
            model.addAttribute("updateUserError", e.message)
            // Retrieve current user and overlay request values to maintain form state
            val user = appUserService.findById(userId)
            user.username = request.username
            user.email = request.email
            user.displayName = request.displayName
            request.role?.let { user.role = it }
            model.addAttribute("editUser", user)
            return "admin/dashboard :: editUserRow"
        }
    }

    // HTMX Endpoint for Syllabus Import
    @PostMapping("/syllabus/import")
    fun importSyllabus(model: Model): String {
        try {
            val result = syllabusImporterService.importAllAiParsedJson()
            model.addAttribute("importResult", result)
            return "admin/dashboard :: importSuccess"
        } catch (e: Exception) {
            model.addAttribute("importError", e.message ?: "Unknown error occurred")
            return "admin/dashboard :: importFailure"
        }
    }

    // HTMX Endpoint for Knowledge Index Rebuild
    @PostMapping("/knowledge/rebuild")
    fun rebuildKnowledgeIndex(model: Model): String {
        return try {
            val report = knowledgeIndexService?.rebuildAll()
            val status = knowledgeIndexService?.getStatus()
            model.addAttribute("rebuildReport", report)
            model.addAttribute("knowledgeStatus", status)
            "admin/dashboard :: rebuildStatus"
        } catch (e: Exception) {
            model.addAttribute("rebuildError", e.message ?: "Unknown error during knowledge index rebuild")
            "admin/dashboard :: rebuildFailure"
        }
    }

    // HTMX Endpoint for Polling Knowledge Index Status
    @GetMapping("/knowledge/status")
    fun knowledgeStatus(model: Model): String {
        val status = knowledgeIndexService?.getStatus()
        model.addAttribute("knowledgeStatus", status)
        return "admin/dashboard :: rebuildStatus"
    }
}