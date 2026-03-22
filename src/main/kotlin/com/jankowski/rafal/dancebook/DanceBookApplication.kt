package com.jankowski.rafal.dancebook

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DanceBookApplication

fun main(args: Array<String>) {
    runApplication<DanceBookApplication>(*args)
}
