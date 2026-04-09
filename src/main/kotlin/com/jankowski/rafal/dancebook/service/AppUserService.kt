package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import java.util.UUID

interface AppUserService {
    fun findAll(): List<AppUser>
    fun findById(id: UUID): AppUser
    fun findByUsername(username: String): AppUser?
    fun getCurrentUser(): AppUser
}