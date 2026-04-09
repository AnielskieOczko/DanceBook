package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByUsername(username: String): AppUser?
}