package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceFigure
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface DanceFigureRepository : JpaRepository<DanceFigure, UUID>, JpaSpecificationExecutor<DanceFigure> {
    fun findByDanceTypeIdOrderByNameAsc(danceTypeId: UUID): List<DanceFigure>
}

