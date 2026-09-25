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

    /** Notes owned by someone other than [userId] that pin the figure, private ones included. */
    @Query("SELECT COUNT(DISTINCT f.material.id) FROM Figure f WHERE f.danceFigure.id = :figureId AND f.material.owner.id <> :userId")
    fun countOtherUsersNotesUsing(@Param("figureId") figureId: UUID, @Param("userId") userId: UUID): Long

    /** Choreographies owned by someone other than [userId] that use the figure, private ones included. */
    @Query("SELECT COUNT(DISTINCT e.choreography.id) FROM ChoreographyEntry e WHERE e.danceFigure.id = :figureId AND e.choreography.owner.id <> :userId")
    fun countOtherUsersChoreographiesUsing(@Param("figureId") figureId: UUID, @Param("userId") userId: UUID): Long
}
