package com.jankowski.rafal.dancebook

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class DanceBookApplicationTests {

    @Test
    fun contextLoads() {
    }

    @Test
    fun generatePasswordHash() {
        val encoder = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        val rawPassword = "password123"
        val encodedPassword = encoder.encode(rawPassword)
        println("\n\n========================================================")
        println("RAW PASSWORD: $rawPassword")
        println("BCRYPT HASH:  $encodedPassword")
        println("========================================================\n\n")
    }

}
