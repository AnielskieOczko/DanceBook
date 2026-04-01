package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Figure
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FigureRepository : JpaRepository<Figure, UUID> {
    fun findAllByMaterialIdOrderByStartTimeAsc(materialId: UUID): List<Figure>
}