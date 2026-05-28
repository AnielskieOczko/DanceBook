package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Choreography
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ChoreographyRepository : JpaRepository<Choreography, UUID> {

    @Query("SELECT c FROM Choreography c WHERE c.owner = :owner OR c.isPublic = true ORDER BY c.updatedAt DESC")
    fun findVisibleByUser(owner: AppUser): List<Choreography>

    fun findAllByOwner(owner: AppUser): List<Choreography>
}
