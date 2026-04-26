package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import java.util.Optional
import java.util.UUID

interface CommentService {
    fun addComment(materialId: UUID, content: String, author: AppUser): Comment
    fun updateComment(commentId: UUID, content: String, currentUser: AppUser): Comment
    fun deleteComment(commentId: UUID, currentUser: AppUser)
    fun getCommentsForMaterial(materialId: UUID): List<Comment>
    fun findCommentById(id: UUID): Comment
}