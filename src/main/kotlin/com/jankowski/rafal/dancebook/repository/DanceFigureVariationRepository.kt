package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceFigureVariation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DanceFigureVariationRepository : JpaRepository<DanceFigureVariation, UUID> {
    fun findByDanceFigureId(danceFigureId: UUID): List<DanceFigureVariation>
}
