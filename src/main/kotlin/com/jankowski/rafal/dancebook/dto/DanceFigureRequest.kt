package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.DanceClass
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class DanceFigureRequest(
    @field:NotBlank
    val name: String = "",

    @field:NotNull
    val danceTypeId: UUID? = null,

    val danceClass: DanceClass? = null,

    val alternativeTiming: String? = null
)
