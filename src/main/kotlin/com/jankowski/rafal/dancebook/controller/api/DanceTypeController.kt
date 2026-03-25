package com.jankowski.rafal.dancebook.controller.api

import com.jankowski.rafal.dancebook.dto.DanceTypeRequest
import com.jankowski.rafal.dancebook.dto.DanceTypeResponse
import com.jankowski.rafal.dancebook.dto.toResponse
import com.jankowski.rafal.dancebook.service.DanceTypeService
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
@RequestMapping("/danceTypes")
class DanceTypeController(
    private val danceTypeService: DanceTypeService
) {

    @GetMapping
    fun getDanceTypes(): List<DanceTypeResponse> {
        return danceTypeService.findAll().map { it.toResponse() }
    }

    @GetMapping("/{danceTypeId}")
    fun getDanceTypeById(@PathVariable danceTypeId: UUID): DanceTypeResponse {
        return danceTypeService.findById(danceTypeId).toResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createDanceType(@Valid @RequestBody request: DanceTypeRequest): DanceTypeResponse {
        return danceTypeService.create(request).toResponse()
    }

    @PutMapping("/{danceTypeId}")
    fun updateDanceType(@PathVariable danceTypeId: UUID, @Valid @RequestBody request: DanceTypeRequest): DanceTypeResponse {
        return danceTypeService.update(danceTypeId, request).toResponse()
    }

    @DeleteMapping("/{danceTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDanceTypeById(@PathVariable danceTypeId: UUID) {
        danceTypeService.delete(danceTypeId)
    }

}