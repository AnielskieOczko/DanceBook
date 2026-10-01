package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jankowski.rafal.dancebook.dto.RichTextLengthValidator
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorFactory
import jakarta.validation.Validation
import jakarta.validation.Validator

/** Shared wiring for the draft tests: the mapper the app builds, and a real Bean Validation validator. */
object DraftTestSupport {

    /** Kotlin and java.time support, dates as ISO strings, as Spring Boot configures it. */
    fun mapper(): ObjectMapper =
        jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** `@RichTextLength` asks Spring for its `RichTextService`; outside Spring we hand it one. */
    fun validator(): Validator {
        val factory = Validation.byDefaultProvider().configure()
            .constraintValidatorFactory(object : ConstraintValidatorFactory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ConstraintValidator<*, *>> getInstance(key: Class<T>): T =
                    if (key == RichTextLengthValidator::class.java) {
                        RichTextLengthValidator(RichTextServiceImpl()) as T
                    } else {
                        key.getDeclaredConstructor().newInstance()
                    }

                override fun releaseInstance(instance: ConstraintValidator<*, *>) {}
            })
            .buildValidatorFactory()
        return factory.validator
    }
}
