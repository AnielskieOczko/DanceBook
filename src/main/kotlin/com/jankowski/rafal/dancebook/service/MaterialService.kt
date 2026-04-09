package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface MaterialService {
    fun findById(id: UUID): Material
    fun create(materialRequest: MaterialRequest): Material
    fun update(id: UUID, request: MaterialRequest): Material
    fun delete(id: UUID)
    fun findAll(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        minRating: Short? = null,
        nameSearch: String? = null,
        pageable: Pageable
    ): Page<Material>

    fun addFigure(materialId: UUID, request: FigureRequest): Figure
    fun removeFigure(materialId: UUID, figureId: UUID)
    fun findFiguresByMaterial(materialId: UUID): List<Figure>
}