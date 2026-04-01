package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class FigureRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 255)
    val name: String = "",

    @field:Min(0)
    val startTime: Int = 0,

    @field:Min(0)
    val endTime: Int = 0,
)