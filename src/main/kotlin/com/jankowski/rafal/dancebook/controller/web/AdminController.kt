package com.jankowski.rafal.dancebook.controller.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.dto.UserCreateRequest
import com.jankowski.rafal.dancebook.dto.UserUpdateRequest
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.StorageCleanupLogRepository
import com.jankowski.rafal.dancebook.service.GoogleDriveService
import com.jankowski.rafal.dancebook.service.StorageCleanupJob
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.time.LocalDateTime
import java.util.UUID

data class AdminDriveFileDto(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val createdTime: Long,
    val isOrphaned: Boolean
)

data class LogPresentation(
    val executedAt: LocalDateTime,
    val filesDeletedCount: Int,
    val isDryRun: Boolean,
    val deletedFiles: List<GoogleDriveService.DriveFileInfo>
)

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')") // Only you can enter
class AdminController(
    private val appUserRepository: AppUserRepository,
    private val appUserService: com.jankowski.rafal.dancebook.service.AppUserService,
    private val materialRepository: MaterialRepository,
    private val storageCleanupLogRepository: StorageCleanupLogRepository,
    private val storageCleanupJob: StorageCleanupJob,
    private val googleDriveService: GoogleDriveService,
    private val objectMapper: ObjectMapper
) {
    @GetMapping
    fun dashboard(model: Model): String {
        // Provide basic stats to the template
        model.addAttribute("totalUsers", appUserRepository.count())
        model.addAttribute("totalMaterials", materialRepository.count())
        
        // Provide fast lists
        model.addAttribute("users", appUserRepository.findAll())

        // Fetch logs and parse JSON payload intelligently
        val rawLogs = storageCleanupLogRepository.findAllByOrderByExecutedAtDesc()
        val presentationLogs = rawLogs.map { log ->
            val filesList = try {
                if (!log.details.isNullOrBlank() && log.details!!.startsWith("[")) {
                     objectMapper.readValue(log.details, object : TypeReference<List<GoogleDriveService.DriveFileInfo>>() {})
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            LogPresentation(
                executedAt = log.executedAt,
                filesDeletedCount = log.filesDeletedCount,
                isDryRun = log.isDryRun,
                deletedFiles = filesList
            )
        }

        model.addAttribute("cleanupLogs", presentationLogs)

        return "admin/dashboard"
    }

    // HTMX Endpoint for Lazy Loading Heavy Google Drive Files
    @GetMapping("/storage/files")
    fun fetchDriveFiles(model: Model): String {
        val driveFiles = googleDriveService.listFilesInFolder()
        val dataBaseIds = materialRepository.findAllDriveFileIds()

        var orphanedSize = 0L
        var linkedSize = 0L
        var orphanedCount = 0
        var linkedCount = 0

        val filesDto = driveFiles.map { file ->
            val orphaned = !dataBaseIds.contains(file.id)
            if (orphaned) {
                orphanedSize += (file.size ?: 0L)
                orphanedCount++
            } else {
                linkedSize += (file.size ?: 0L)
                linkedCount++
            }

            AdminDriveFileDto(
                id = file.id,
                name = file.name,
                sizeBytes = file.size,
                createdTime = file.createdTime,
                isOrphaned = orphaned
            )
        }
        
        model.addAttribute("driveFiles", filesDto)
        model.addAttribute("orphanedSize", orphanedSize)
        model.addAttribute("linkedSize", linkedSize)
        model.addAttribute("totalSize", orphanedSize + linkedSize)

        model.addAttribute("orphanedCount", orphanedCount)
        model.addAttribute("linkedCount", linkedCount)
        model.addAttribute("totalCount", orphanedCount + linkedCount)
        
        return "admin/dashboard :: driveSection"
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

    @GetMapping("/users/form")
    fun showCreateUserForm(model: Model): String {
        return "admin/dashboard :: createUserForm"
    }

    @GetMapping("/users/form/cancel")
    fun cancelCreateUserForm(): String {
        return "admin/dashboard :: empty"
    }

    @PostMapping("/users")
    fun createUser(@Valid request: UserCreateRequest, model: Model): String {
        try {
            appUserService.createUser(request)
            model.addAttribute("createUserSuccess", "Account created successfully!")
        } catch (e: Exception) {
            model.addAttribute("createUserError", e.message)
            model.addAttribute("showCreateForm", true)
        }
        model.addAttribute("users", appUserRepository.findAll())
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
    fun editUser(@PathVariable id: String, @Valid request: UserUpdateRequest, model: Model): String {
        try {
            val updatedUser = appUserService.updateUser(UUID.fromString(id), request)
            model.addAttribute("user", updatedUser)
            return "admin/dashboard :: userRow"
        } catch (e: Exception) {
            model.addAttribute("updateUserError", e.message)
            // Retrieve current user and overlay request values to maintain form state
            val user = appUserService.findById(UUID.fromString(id))
            user.username = request.username
            user.email = request.email
            user.displayName = request.displayName
            user.role = request.role
            model.addAttribute("editUser", user)
            return "admin/dashboard :: editUserRow"
        }
    }
}