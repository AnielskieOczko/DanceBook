package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.LocalDateTime
import java.util.UUID

data class MaterialRequest(
    @field:NotBlank 
    @field:Size(min = 2, max = 255)
    val name: String = "",
    
    @field:RichTextLength(max = 2000)
    val description: String? = null,
    
    val danceCategoryId: UUID? = null,
    val danceTypeId: UUID? = null,
    
    @field:Min(1) @field:Max(5) 
    val rating: Short? = null,
    
    @field:URL
    val videoLink: String? = null,
    
    @field:URL
    val sourceLink: String? = null,
    
    @field:Size(max = 255)
    val driveFileId: String? = null,
    
    val isPublic: Boolean? = null,

    val version: Long,

    /**
     * Set only when the note is written from a training session (#147): on create, the new note
     * is linked to that session. The web form carries it as a hidden field; it is not stored on
     * the note itself.
     */
    val trainingEventId: UUID? = null,

    /**
     * Create only. Catalog figures to pin once the note exists. Set when the note comes from an
     * assistant draft (#149): the create form has no figure picker, so it shows them as chips and
     * carries them as one comma-separated hidden field. Not stored on the note itself.
     */
    val figureIds: List<UUID> = emptyList(),

    /** Create only. Record [trainingEventId]'s session as attended, for a note written from a draft. */
    val markAttended: Boolean = false
)

data class MaterialResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val danceType: DanceTypeResponse?,
    val rating: Short?,
    val videoLink: String?,
    val sourceLink: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val isPublic: Boolean = false,
    val visibility: com.jankowski.rafal.dancebook.model.Visibility = com.jankowski.rafal.dancebook.model.Visibility.PRIVATE,
    val version: Long,
)

data class DanceTypeResponse(
    val id: UUID,
    val name: String,
    val predefined: Boolean,
    val categoryId: UUID?,
    val categoryName: String?,
    val categoryImageFilename: String? = null
)

data class DanceCategoryResponse(
    val id: UUID,
    val name: String,
    val predefined: Boolean,
    val imageFilename: String? = null
)

data class DanceTypeRequest(
    @field:NotBlank val name: String,
    @field:NotNull val categoryId: UUID? = null,
)

data class DanceCategoryRequest(
    @field:NotBlank val name: String,
    val image: org.springframework.web.multipart.MultipartFile? = null
)

data class MaterialFigureCount(
    val materialId: UUID,
    val count: Long
)

