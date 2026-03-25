package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceCategoryRequest
import com.jankowski.rafal.dancebook.model.DanceCategory
import java.util.UUID

interface DanceCategoryService {

    fun findAll(): List<DanceCategory>
    fun create(request: DanceCategoryRequest): DanceCategory
    fun update(id: UUID, request: DanceCategoryRequest): DanceCategory
    fun delete(id: UUID)
    fun findById(id: UUID): DanceCategory
}