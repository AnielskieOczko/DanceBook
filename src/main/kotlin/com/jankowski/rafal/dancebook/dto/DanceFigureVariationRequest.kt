package com.jankowski.rafal.dancebook.dto

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class DanceFigureVariationRequest(
    val id: UUID? = null,

    @field:NotBlank
    val name: String = "Standard",

    @field:NotBlank
    val timing: String = "",

    val isDefault: Boolean = false,

    val startingFootLeader: String? = null,
    val endingFootLeader: String? = null,
    val startingFootFollower: String? = null,
    val endingFootFollower: String? = null,
    val startingPosition: String? = null,
    val endingPosition: String? = null,

    val steps: MutableList<DanceFigureStepRequest> = mutableListOf()
)
