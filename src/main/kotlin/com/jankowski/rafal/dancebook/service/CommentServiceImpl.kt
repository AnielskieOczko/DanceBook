package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import jakarta.persistence.EntityNotFoundException
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.security.access.AccessDeniedException

@Service
@Transactional
class CommentServiceImpl(
    private val commentRepository: CommentRepository,
    private val materialRepository: MaterialRepository,
): CommentService {

    companion object {
        private val logger = LoggerFactory.getLogger(CommentServiceImpl::class.java)
    }

    override fun addComment(
        materialId: UUID,
        content: String,
        author: AppUser
    ): Comment {
        logger.info("Adding comment for material $materialId")
        val material = materialRepository.findById(materialId)
            .orElseThrow { EntityNotFoundException("Material with id $materialId does not exist") }

        val comment = Comment().apply {
            this.content = content
            this.author = author
            this.material = material
        }
        return commentRepository.save(comment)
    }

    @Transactional
    override fun updateComment(commentId: UUID, content: String, currentUser: AppUser): Comment {
        val comment = commentRepository.findById(commentId)
            .orElseThrow { EntityNotFoundException("Comment not found") }

        // Security check: Only author can edit
        if (comment.author?.id != currentUser.id) {
            throw AccessDeniedException("You are not allowed to change this comment to this user")
        }

        comment.content = content
        comment.updatedAt = LocalDateTime.now()
        return commentRepository.save(comment)
    }
    @Transactional
    override fun deleteComment(commentId: UUID, currentUser: AppUser) {
        val comment = commentRepository.findById(commentId)
            .orElseThrow { EntityNotFoundException("Comment not found") }

        // Security check: Only author or Admin can delete
        if (comment.author?.id != currentUser.id && currentUser.role != Role.ADMIN) {
            throw AccessDeniedException("Not authorized to delete this comment")
        }

        commentRepository.delete(comment)
    }
    override fun getCommentsForMaterial(materialId: UUID): List<Comment> {
        return commentRepository.findByMaterialIdOrderByCreatedAtDesc(materialId)
    }

    override fun findCommentById(id: UUID): Comment {
        logger.info("Retrieving comment for id $id")
        return commentRepository.findById(id).orElseThrow { EntityNotFoundException("Comment not found") }
    }


}