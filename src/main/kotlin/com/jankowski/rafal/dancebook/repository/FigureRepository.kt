package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.dto.MaterialFigureCount
import com.jankowski.rafal.dancebook.model.Figure
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FigureRepository : JpaRepository<Figure, UUID> {
    fun findAllByMaterialIdOrderByStartTimeAsc(materialId: UUID): List<Figure>

    @Query("SELECT new com.jankowski.rafal.dancebook.dto.MaterialFigureCount(f.material.id, COUNT(f)) FROM Figure f WHERE f.material.id IN :materialIds GROUP BY f.material.id")
    fun countFiguresByMaterialIds(@Param("materialIds") materialIds: Collection<UUID>): List<MaterialFigureCount>
}