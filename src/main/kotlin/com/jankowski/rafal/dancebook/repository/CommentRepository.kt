package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Comment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CommentRepository: JpaRepository<Comment, UUID> {
    fun findByMaterialIdOrderByCreatedAtDesc(materialId: UUID): List<Comment>
    fun countByMaterialId(materialId: UUID): Long
}