package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import java.util.UUID

interface DanceFigureService {
    companion object {
        /** Shown when a save loses an optimistic-locking race: figures are edited by everyone. */
        const val CONFLICT_MESSAGE =
            "Someone else saved this figure while you were editing it. Reload the figure to see their changes, then make yours again."
    }

    fun findAll(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        danceClass: DanceClass? = null,
        nameSearch: String? = null,
        sortBy: String? = null,
        hasSteps: Boolean? = null
    ): List<DanceFigure>
    fun findById(id: UUID): DanceFigure
    fun findByDanceType(danceTypeId: UUID): List<DanceFigure>
    fun findFigureIdsWithSteps(figureIds: Collection<UUID>): Set<UUID>
    fun create(request: DanceFigureRequest): DanceFigure
    fun update(id: UUID, request: DanceFigureRequest): DanceFigure
    fun delete(id: UUID)
}

