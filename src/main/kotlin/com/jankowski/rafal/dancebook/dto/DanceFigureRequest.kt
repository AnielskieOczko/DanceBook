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
    val alternativeTiming: String? = null,

    val startingFootLeader: String? = null,
    val endingFootLeader: String? = null,
    val startingFootFollower: String? = null,
    val endingFootFollower: String? = null,
    val startingPosition: String? = null,
    val endingPosition: String? = null,

    val precedingFigureNames: List<String> = emptyList(),
    val followingFigureNames: List<String> = emptyList(),

    val notes: String? = null,

    val steps: MutableList<DanceFigureStepRequest> = mutableListOf(),
    val stepSets: MutableList<DanceFigureStepSetRequest> = mutableListOf(),
    val links: MutableList<DanceFigureLinkRequest> = mutableListOf()
) {
    fun getEffectiveStepSets(): List<DanceFigureStepSetRequest> {
        if (stepSets.isNotEmpty() && (stepSets.size > 1 || stepSets[0].steps.isNotEmpty() || stepSets[0].name != "Default")) {
            return stepSets
        }
        if (steps.isNotEmpty()) {
            return listOf(
                DanceFigureStepSetRequest(
                    name = "Default",
                    isDefault = true,
                    steps = steps
                )
            )
        }
        return stepSets
    }
}
