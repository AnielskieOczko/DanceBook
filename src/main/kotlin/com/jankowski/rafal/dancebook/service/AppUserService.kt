package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import java.util.UUID
import com.jankowski.rafal.dancebook.dto.UserCreateRequest
import com.jankowski.rafal.dancebook.dto.PasswordChangeRequest
import com.jankowski.rafal.dancebook.dto.UserUpdateRequest

interface AppUserService {
    fun findAll(): List<AppUser>
    fun findById(id: UUID): AppUser
    fun findByUsername(username: String): AppUser
    fun getCurrentUser(): AppUser
    fun createUser(request: UserCreateRequest): AppUser
    fun changePassword(userId: UUID, request: PasswordChangeRequest)
    fun updateUser(id: UUID, request: UserUpdateRequest): AppUser
}