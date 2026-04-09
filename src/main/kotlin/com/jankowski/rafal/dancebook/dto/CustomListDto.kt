package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class CustomListRequest(
    val name: String,
    val nameFilter: String? = null,
    val danceTypeIds: List<UUID> = emptyList(),
    val danceCategoryIds: List<UUID> = emptyList(),
    val minRating: Short? = null,
    val isPublic: Boolean = false
)

data class CustomListResponse(
    val id: UUID,
    val name: String,
    val nameFilter: String?,
    val danceTypeIds: List<UUID>,
    val danceCategoryIds: List<UUID>,
    val minRating: Short?,
    val isPublic: Boolean,
    val ownerUsername: String
)
