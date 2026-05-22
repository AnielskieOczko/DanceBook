package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.CustomListRequest
import com.jankowski.rafal.dancebook.model.CustomList
import java.util.UUID

interface CustomListService {
    fun findVisibleByCurrentUser(
        typeIds: List<UUID>? = null,
        categoryIds: List<UUID>? = null,
        nameSearch: String? = null,
        sortBy: String? = null
    ): List<CustomList>
    fun findById(id: UUID): CustomList
    fun create(request: CustomListRequest): CustomList
    fun update(id: UUID, request: CustomListRequest): CustomList
    fun delete(id: UUID)
}
