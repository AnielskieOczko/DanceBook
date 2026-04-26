package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.MaterialService
import org.apache.http.HttpResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.util.UUID

@Controller
@RequestMapping( "/materials/{materialId}/comments")
class CommentController(
    private val commentService: CommentService,
    private val appUserService: AppUserService,
    private val materialService: MaterialService
) {

    @PostMapping
    fun addComment(
        @PathVariable materialId: UUID,
        @RequestParam(value = "content", required = false) content: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model,
    ): String {
        val currentUser = appUserService.findByUsername(userDetails.username)
        commentService.addComment(
            materialId = materialId,
            content = content,
            author = currentUser
        )
        val material = materialService.findById(materialId)
        model.addAttribute("material", material)
        model.addAttribute("comments", commentService.getCommentsForMaterial(materialId))
        return "materials/fragments/comments :: comment-list"
    }

    @GetMapping("/{commentId}/edit")
    fun editCommentForm(
        @PathVariable materialId: UUID,
        @PathVariable commentId: UUID,
        model: Model,
    ): String {
        val comment = commentService.findCommentById(commentId)
        val material = materialService.findById(materialId)
        model.addAttribute("material", material)
        model.addAttribute("comment", comment)
        return "materials/fragments/comments :: comment-edit-form"
    }

    @PutMapping("/{commentId}")
    fun updateComment(
        @PathVariable materialId: UUID,
        @PathVariable commentId: UUID,
        @RequestParam(value = "content", required = false) content: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        model: Model,
    ): String {

        val currentUser = appUserService.findByUsername(userDetails.username)
        commentService.updateComment(commentId, content, currentUser)

        val material = materialService.findById(materialId)
        model.addAttribute("material", material)
        model.addAttribute("c", commentService.findCommentById(commentId))
        return "materials/fragments/comments :: comment-item"
    }

    @GetMapping("/{commentId}")
    fun getCommentItem(
        @PathVariable materialId: UUID,
        @PathVariable commentId: UUID,
        model: Model
    ): String {
        val comment = commentService.findCommentById(commentId)
        val material = materialService.findById(materialId)
        model.addAttribute("material", material)
        model.addAttribute("c", comment)
        return "materials/fragments/comments :: comment-item"
    }
    @DeleteMapping("/{commentId}")
    @ResponseBody // HTMX handles 200 OK by removing the element if configured
    fun deleteComment(
        @PathVariable materialId: UUID,
        @PathVariable commentId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ) {
        val currentUser = appUserService.findByUsername(userDetails.username)
        commentService.deleteComment(commentId, currentUser)
    }


}