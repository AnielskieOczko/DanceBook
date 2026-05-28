package com.jankowski.rafal.dancebook.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class StringListConverter : AttributeConverter<List<String>, String> {
    private val objectMapper = jacksonObjectMapper()

    override fun convertToDatabaseColumn(attribute: List<String>?): String? {
        if (attribute == null) return null
        return try {
            objectMapper.writeValueAsString(attribute)
        } catch (e: Exception) {
            null
        }
    }

    override fun convertToEntityAttribute(dbData: String?): List<String> {
        if (dbData.isNullOrBlank()) return emptyList()
        val trimmed = dbData.trim()
        if (trimmed.startsWith("[")) {
            return try {
                objectMapper.readValue<List<String>>(trimmed)
            } catch (e: Exception) {
                // If JSON parsing fails, fallback to splitting
                trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        // Fallback for legacy comma-separated values
        return trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
