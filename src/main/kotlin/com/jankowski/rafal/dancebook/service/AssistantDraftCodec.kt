package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import org.springframework.stereotype.Component

/**
 * Turns a request DTO into the `payload` map of a draft and back. Jackson also writes the DTOs'
 * computed properties (`isRepeating`, `effectiveStepSets`), which have no constructor parameter to
 * read back into. Two settings make reading them harmless: `FAIL_ON_UNKNOWN_PROPERTIES` off, and
 * `USE_GETTERS_AS_SETTERS` off. The second matters because Postgres `jsonb` reorders keys, and when
 * a list-valued computed property arrives before the constructor arguments Jackson otherwise tries
 * to fill the getter's list and fails ("Should never call set() on setterless property").
 */
@Component
@ConditionalOnAssistant
class AssistantDraftCodec(objectMapper: ObjectMapper) {

    private val mapper: ObjectMapper = objectMapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(MapperFeature.USE_GETTERS_AS_SETTERS, false)

    fun toPayload(request: Any): MutableMap<String, Any?> =
        mapper.convertValue(request, object : TypeReference<MutableMap<String, Any?>>() {})

    fun <T> read(payload: Map<String, Any?>, type: Class<T>): T = mapper.convertValue(payload, type)
}
