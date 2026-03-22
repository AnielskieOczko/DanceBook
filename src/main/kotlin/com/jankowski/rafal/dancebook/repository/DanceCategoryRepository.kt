package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.DanceCategory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DanceCategoryRepository: JpaRepository<DanceCategory, UUID> {
}