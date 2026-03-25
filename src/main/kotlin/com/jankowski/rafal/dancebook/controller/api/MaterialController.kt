package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.MaterialResponse
import com.jankowski.rafal.dancebook.dto.toResponse
import com.jankowski.rafal.dancebook.service.MaterialService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/materials")
class MaterialController(
    private val materialService: MaterialService
) {

    @GetMapping
    fun getMaterials(
        @RequestParam(required = false) danceTypeId: UUID?,
        @RequestParam(required = false) danceCategoryId: UUID?,
        @RequestParam(required = false) rating: Short?,
        pageable: Pageable
    ): Page<MaterialResponse> {
        return materialService.findAll(
            typeId = danceTypeId,
            categoryId = danceCategoryId,
            rating = rating,
            pageable = pageable
        ).map { it.toResponse() }
    }

    @GetMapping("/{materialId}")
    fun getMaterialById(@PathVariable materialId: UUID): MaterialResponse {
        return materialService.findById(materialId).toResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createMaterial(@Valid @RequestBody request: MaterialRequest): MaterialResponse {
        return materialService.create(request).toResponse()
    }

    @PutMapping("/{materialId}")
    fun updateMaterial(@PathVariable materialId: UUID,@Valid @RequestBody materialRequest: MaterialRequest): MaterialResponse {
        return materialService.update(materialId, materialRequest).toResponse()
    }

    @DeleteMapping("/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMaterial(@PathVariable materialId: UUID) {
        materialService.delete(materialId)
    }

}