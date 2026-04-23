package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val materialRepository: MaterialRepository,
    private val appUserRepository: AppUserRepository
) {
    fun getCommentsForMaterial(materialId: UUID): List<Comment> {
        return commentRepository.findByMaterialIdOrderByCreatedAtDesc(materialId)
    }

    @Transactional
    fun addComment(materialId: UUID, username: String, content: String): Comment {
        val material = materialRepository.findById(materialId).orElseThrow()
        val user = appUserRepository.findByUsername(username) ?: throw IllegalArgumentException("User not found")
        
        val comment = Comment().apply {
            this.material = material
            this.user = user
            this.content = content.trim()
        }
        return commentRepository.save(comment)
    }

    @Transactional
    fun deleteComment(commentId: UUID, username: String) {
        val comment = commentRepository.findById(commentId).orElseThrow()
        // Ensure only the author (or an admin) can delete
        if (comment.user.username != username) {
            throw SecurityException("Unauthorized to delete this comment")
        }
        commentRepository.delete(comment)
    }

    @Transactional
    fun deleteAndGetMaterialId(commentId: UUID, username: String): UUID {
        val comment = commentRepository.findById(commentId).orElseThrow()
        if (comment.user.username != username) {
            throw SecurityException("Unauthorized to delete this comment")
        }
        val materialId = comment.material.id ?: throw IllegalStateException("Material ID is null")
        commentRepository.delete(comment)
        return materialId
    }
}
