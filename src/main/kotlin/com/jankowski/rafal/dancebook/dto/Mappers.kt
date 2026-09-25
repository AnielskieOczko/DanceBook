package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material

fun DanceType.toResponse() = DanceTypeResponse(
    id = id ?: throw IllegalStateException("DanceType must have an id"),
    name = name,
    predefined = predefined,
    categoryId = category?.id,
    categoryName = category?.name,
    categoryImageFilename = category?.imageFilename
)

fun DanceTypeRequest.toEntity() = DanceType().apply {
    name = this@toEntity.name
}

fun DanceCategory.toResponse() = DanceCategoryResponse(
    id = id ?: throw IllegalStateException("DanceCategory must have an id"),
    name = name,
    predefined = predefined,
    imageFilename = imageFilename
)

fun DanceCategoryRequest.toEntity() = DanceCategory().apply {
    name = this@toEntity.name
}

fun Material.toResponse() = MaterialResponse(
    id = id!!,
    name = name,
    description = description,
    danceType = danceType?.toResponse(),
    rating = rating,
    videoLink = videoLink,
    sourceLink = sourceLink,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPublic = isPublic,
    visibility = visibility,
    version = version,
)

fun MaterialRequest.toEntity(existingMaterial: Material = Material()) = existingMaterial.apply {
    name = this@toEntity.name
    description = this@toEntity.description
    rating = this@toEntity.rating
    videoLink = this@toEntity.videoLink
    sourceLink = this@toEntity.sourceLink
    visibility = if (this@toEntity.isPublic != null) {
        if (this@toEntity.isPublic == true) com.jankowski.rafal.dancebook.model.Visibility.PUBLIC else com.jankowski.rafal.dancebook.model.Visibility.PRIVATE
    } else {
        existingMaterial.visibility
    }
    version = this@toEntity.version
}