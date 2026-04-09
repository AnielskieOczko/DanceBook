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
        val requestAttributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() 
            as? org.springframework.web.context.request.ServletRequestAttributes
        val request = requestAttributes?.request
        
        val usernameCookie = request?.cookies?.find { it.name == "CURRENT_USER" }?.value
        val username = usernameCookie ?: DEFAULT_USERNAME

        return findByUsername(username)
            ?: throw EntityNotFoundException("User '$username' not found. Run seed migration V6.")
    }
}