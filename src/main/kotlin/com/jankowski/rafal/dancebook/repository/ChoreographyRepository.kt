package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Choreography
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ChoreographyRepository : JpaRepository<Choreography, UUID>, JpaSpecificationExecutor<Choreography> {

    fun findAllByOwner(owner: AppUser): List<Choreography>
}
