package com.jankowski.rafal.dancebook

import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class DanceBookApplicationTests {

    @Test
    fun generatePasswordHash() {
        val encoder = BCryptPasswordEncoder()
        val rawPassword = "password123"
        val encodedPassword = encoder.encode(rawPassword)
        println("\n\n========================================================")
        println("RAW PASSWORD: $rawPassword")
        println("BCRYPT HASH:  $encodedPassword")
        println("========================================================\n\n")
    }

}

