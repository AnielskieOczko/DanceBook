package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class CustomListRequest(
    @field:NotBlank
    val name: String = "",
    val nameFilter: String? = null,
    val danceTypeIds: List<UUID> = emptyList(),
    val danceCategoryIds: List<UUID> = emptyList(),
    @field:Min(1) @field:Max(5)
    val minRating: Short? = null,
    val isPublic: Boolean = false,
    val image: MultipartFile? = null
)

data class CustomListResponse(
    val id: UUID,
    val name: String,
    val nameFilter: String?,
    val danceTypeIds: List<UUID>,
    val danceCategoryIds: List<UUID>,
    val minRating: Short?,
    val isPublic: Boolean,
    val imageFilename: String?,
    val ownerUsername: String
)
