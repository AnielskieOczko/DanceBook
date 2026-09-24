package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceFigure
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface DanceFigureRepository : JpaRepository<DanceFigure, UUID>, JpaSpecificationExecutor<DanceFigure> {
    fun findByDanceTypeIdOrderByNameAsc(danceTypeId: UUID): List<DanceFigure>

    @Query("SELECT DISTINCT s.danceFigure.id FROM DanceFigureStepSet s WHERE s.danceFigure.id IN :figureIds")
    fun findFigureIdsWithSteps(@Param("figureIds") figureIds: Collection<UUID>): Set<UUID>
}

