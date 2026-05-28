package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.ChoreographyEntryRequest
import com.jankowski.rafal.dancebook.dto.ChoreographyRequest
import com.jankowski.rafal.dancebook.model.Choreography
import java.util.UUID

interface ChoreographyService {
    fun findByCurrentUser(): List<Choreography>
    fun findById(id: UUID): Choreography
    fun create(request: ChoreographyRequest): Choreography
    fun update(id: UUID, request: ChoreographyRequest): Choreography
    fun delete(id: UUID)
    fun duplicate(id: UUID): Choreography
    fun addEntry(choreographyId: UUID, request: ChoreographyEntryRequest): Choreography
    fun removeEntry(choreographyId: UUID, entryId: UUID): Choreography
    fun reorderEntries(choreographyId: UUID, entryIds: List<UUID>): Choreography
    fun updateEntry(choreographyId: UUID, entryId: UUID, request: ChoreographyEntryRequest): Choreography
}
