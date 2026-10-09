package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.MedalLevel
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class ChoreographyRequest(
    @field:NotBlank
    val name: String = "",

    val description: String? = null,

    @field:NotNull
    val danceTypeId: UUID? = null,

    val isPublic: Boolean = false,

    val medalLevel: MedalLevel? = null
)
