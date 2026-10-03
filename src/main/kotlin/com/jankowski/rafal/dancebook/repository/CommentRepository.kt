package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.Comment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CommentRepository: JpaRepository<Comment, UUID>, JpaSpecificationExecutor<Comment> {
    fun findByMaterialIdOrderByCreatedAtDesc(materialId: UUID): List<Comment>
    fun countByMaterialId(materialId: UUID): Long

    @Query("SELECT c.id FROM Comment c WHERE c.material.id = :materialId")
    fun findIdsByMaterialId(@Param("materialId") materialId: UUID): List<UUID>
}