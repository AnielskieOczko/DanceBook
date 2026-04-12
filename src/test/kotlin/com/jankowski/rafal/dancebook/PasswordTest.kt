package com.jankowski.rafal.dancebook

import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class PasswordTest {
    @Test
    fun generatePassword() {
        val encoder = BCryptPasswordEncoder()
        println("Generated Hash for 'password123': " + encoder.encode("password123"))
    }
}
