package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material

fun DanceType.toResponse() = DanceTypeResponse(
    id = id!!,
    name = name,
    predefined
)

fun DanceTypeRequest.toEntity() = DanceType().apply {
    name = this@toEntity.name
}

fun DanceCategory.toResponse() = DanceCategoryResponse(
    id = id!!,
    name = name,
    predefined = predefined,
)

fun DanceCategoryRequest.toEntity() = DanceCategory().apply {
    name = this@toEntity.name
}

fun Material.toResponse() = MaterialResponse(
    id = id!!,
    name = name,
    description = description,
    danceType = danceType?.toResponse(),
    danceCategory = danceCategory?.toResponse(),
    rating = rating,
    videoLink = videoLink,
    sourceLink = sourceLink,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version,
)

fun MaterialRequest.toEntity(existingMaterial: Material = Material()) = existingMaterial.apply {
    name = this@toEntity.name
    description = this@toEntity.description
    rating = this@toEntity.rating
    videoLink = this@toEntity.videoLink
    sourceLink = this@toEntity.videoLink
    version = this@toEntity.version
}