package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserServiceImpl(
    private val appUserRepository: AppUserRepository,
) : AppUserService {

    companion object {
        private const val DEFAULT_USERNAME = "rafal"
        private val log = LoggerFactory.getLogger(AppUserServiceImpl::class.java)
    }

    override fun findAll(): List<AppUser> {
        log.debug("Retrieving all users")
        return appUserRepository.findAll()
    }

    override fun findById(id: UUID): AppUser {
        log.debug("Retrieving user by id {}", id)
        return appUserRepository.findById(id).orElseThrow {
            EntityNotFoundException("Could not find user with id $id")
        }
    }

    override fun findByUsername(username: String): AppUser? {
        log.debug("Retrieving user by username {}", username)
        return appUserRepository.findByUsername(username)
    }

    override fun getCurrentUser(): AppUser {
        return findByUsername(DEFAULT_USERNAME)
            ?: throw EntityNotFoundException("Default user '$DEFAULT_USERNAME' not found. Run seed migration V6.")
    }
}