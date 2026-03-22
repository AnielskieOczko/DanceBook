package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceTypeRequest
import com.jankowski.rafal.dancebook.model.DanceType
import java.util.UUID

interface DanceTypeService {
    fun findAll(): List<DanceType>
    fun findById(id: UUID): DanceType
    fun create(request: DanceTypeRequest): DanceType
    fun update(id: UUID, request: DanceTypeRequest): DanceType
    fun delete(danceTypeId: UUID)
}