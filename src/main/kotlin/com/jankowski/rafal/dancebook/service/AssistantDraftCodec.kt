package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import org.springframework.stereotype.Component

/**
 * Turns a request DTO into the `payload` map of a draft and back. It reads with
 * `FAIL_ON_UNKNOWN_PROPERTIES` off because Jackson also writes the DTOs' computed properties
 * (`isRepeating`, `effectiveStepSets`), which have no constructor parameter to read back into.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftCodec(objectMapper: ObjectMapper) {

    private val mapper: ObjectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun toPayload(request: Any): MutableMap<String, Any?> =
        mapper.convertValue(request, object : TypeReference<MutableMap<String, Any?>>() {})

    fun <T> read(payload: Map<String, Any?>, type: Class<T>): T = mapper.convertValue(payload, type)
}
