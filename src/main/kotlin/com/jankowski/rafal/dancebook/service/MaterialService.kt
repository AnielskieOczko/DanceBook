package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceCategoryRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.Material
import java.util.UUID

interface MaterialService {
    fun findById(id: UUID): Material
    fun update(id: UUID, request: MaterialRequest): Material
    fun delete(id: UUID)
    fun findAll(typeId: UUID?, categoryId: UUID?, rating: Short?): List<Material>
}