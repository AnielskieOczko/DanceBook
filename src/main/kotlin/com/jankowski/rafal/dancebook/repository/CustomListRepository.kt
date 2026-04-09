package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.CustomList
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomListRepository : JpaRepository<CustomList, UUID> {

    /**
     * Returns all lists visible to the given user:
     * - Lists owned by that user (public or private)
     * - All public lists from other users
     */
    @Query("SELECT cl FROM CustomList cl WHERE cl.owner = :owner OR cl.isPublic = true ORDER BY cl.name")
    fun findVisibleByUser(owner: AppUser): List<CustomList>

    fun findAllByOwner(owner: AppUser): List<CustomList>
}
