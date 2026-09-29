package com.jankowski.rafal.dancebook.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {

    /** Injected wherever "now" decides what a page says, so tests can pin it. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
