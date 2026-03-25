package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.DanceCategoryRequest
import com.jankowski.rafal.dancebook.dto.DanceCategoryResponse
import com.jankowski.rafal.dancebook.dto.toResponse
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/dance-categories")
class DanceCategoryController(
    private val danceCategoryService: DanceCategoryService
) {

    @GetMapping
    fun getDanceCategories(): List<DanceCategoryResponse> {
        return danceCategoryService.findAll().map { it.toResponse() }
    }

    @GetMapping("/{danceCategoryId}")
    fun getCategoryById(@PathVariable danceCategoryId: UUID): DanceCategoryResponse {
        return danceCategoryService.findById(danceCategoryId).toResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createDanceCategory(@Valid @RequestBody request: DanceCategoryRequest): DanceCategoryResponse {
        return danceCategoryService.create(request).toResponse()
    }

    @PutMapping("/{danceCategoryId}")
    fun updateDanceCategory(@PathVariable danceCategoryId: UUID,@Valid @RequestBody request: DanceCategoryRequest): DanceCategoryResponse {
        return danceCategoryService.update(danceCategoryId, request).toResponse()
    }

    @DeleteMapping("/{danceCategoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDanceCategory(@PathVariable danceCategoryId: UUID) {
        danceCategoryService.delete(danceCategoryId)
    }



}