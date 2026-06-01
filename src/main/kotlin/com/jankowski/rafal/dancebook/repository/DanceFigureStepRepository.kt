package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceFigureStep
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DanceFigureStepRepository : JpaRepository<DanceFigureStep, UUID> {
    fun deleteByDanceFigureId(danceFigureId: UUID)
}
