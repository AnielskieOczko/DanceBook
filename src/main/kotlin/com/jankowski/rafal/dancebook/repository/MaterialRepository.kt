package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Material
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface MaterialRepository: JpaRepository<Material, UUID> {

    fun findByDanceTypeId(danceTypeId: UUID): List<Material>
    fun findByDanceCategoryId(danceCategoryId: UUID): List<Material>
    fun findByRating(ratting: Short): List<Material>
}