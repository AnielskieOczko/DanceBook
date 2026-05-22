package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class FigureRequest(
    val id: UUID? = null,

    @field:NotNull
    val danceFigureId: UUID? = null,

    @field:Min(0)
    val startTime: Int = 0,

    @field:Min(0)
    val endTime: Int = 0,
)