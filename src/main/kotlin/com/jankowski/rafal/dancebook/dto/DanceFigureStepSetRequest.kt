package com.jankowski.rafal.dancebook.dto

import java.util.UUID

data class DanceFigureStepSetRequest(
    val id: UUID? = null,
    val name: String = "Default",
    val isDefault: Boolean = false,
    val steps: MutableList<DanceFigureStepRequest> = mutableListOf()
)
