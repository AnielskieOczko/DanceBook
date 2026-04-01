package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.LocalDateTime
import java.util.UUID

data class MaterialRequest(
    @field:NotBlank 
    @field:Size(min = 2, max = 255)
    val name: String,
    
    @field:Size(max = 2000)
    val description: String? = null,
    
    val danceTypeId: UUID? = null,
    val danceCategoryId: UUID? = null,
    
    @field:Min(1) @field:Max(5) 
    val rating: Short? = null,
    
    @field:URL
    val videoLink: String? = null,
    
    @field:URL
    val sourceLink: String? = null,
    
    @field:Size(max = 255)
    val driveFileId: String? = null,
    
    val version: Long
)

data class MaterialResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val danceType: DanceTypeResponse?,
    val danceCategory: DanceCategoryResponse?,
    val rating: Short?,
    val videoLink: String?,
    val sourceLink: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val version: Long,
)

data class DanceTypeResponse(
    val id: UUID,
    val name: String,
    val predefined: Boolean
)

data class DanceCategoryResponse(
    val id: UUID,
    val name: String,
    val predefined: Boolean
)

data class DanceTypeRequest(
    @field:NotBlank val name: String,
)

data class DanceCategoryRequest(
    @field:NotBlank val name: String,
)

