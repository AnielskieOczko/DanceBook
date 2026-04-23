package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.service.CommentService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Controller
class CommentWebController(
    private val commentService: CommentService
) {

    @GetMapping("/materials/{materialId}/comments")
    fun listComments(@PathVariable materialId: UUID, model: Model, @AuthenticationPrincipal userDetails: UserDetails): String {
        val comments = commentService.getCommentsForMaterial(materialId)
        model.addAttribute("comments", comments)
        model.addAttribute("materialId", materialId)
        model.addAttribute("commentCount", comments.size)
        model.addAttribute("currentUsername", userDetails.username)
        return "comments/fragment :: commentSection"
    }

    @PostMapping("/materials/{materialId}/comments", produces = ["text/html;charset=UTF-8"])
    fun addComment(
        @PathVariable materialId: UUID,
        @RequestParam content: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model
    ): String {
        if (content.isNotBlank()) {
            commentService.addComment(materialId, userDetails.username, content)
        }
        return listComments(materialId, model, userDetails)
    }

    @DeleteMapping("/comments/{commentId}", produces = ["text/html;charset=UTF-8"])
    fun deleteComment(
        @PathVariable commentId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model
    ): String {
        val materialId = commentService.deleteAndGetMaterialId(commentId, userDetails.username)
        val newCount = commentService.getCommentsForMaterial(materialId).size
        model.addAttribute("commentCount", newCount)
        return "comments/fragment :: countUpdateOobOnly"
    }
}
