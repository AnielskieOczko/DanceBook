package com.jankowski.rafal.dancebook.controller.web

import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalNotFoundExceptionHandler {

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleNotFound(ex: EntityNotFoundException, response: HttpServletResponse) {
        response.sendError(HttpStatus.NOT_FOUND.value(), ex.message)
    }
}
