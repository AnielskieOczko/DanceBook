package com.jankowski.rafal.dancebook.scripts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jankowski.rafal.dancebook.service.SyllabusImporterService.AiParsedFigureDto
import java.io.File
import java.nio.file.Paths
import java.util.UUID

fun main() {
    val parsedDir = File("docs/figures steps/parsed")
    if (!parsedDir.exists() || !parsedDir.isDirectory) {
        System.err.println("Error: Parsed figures directory not found at docs/figures steps/parsed")
        System.exit(1)
    }

    val files = parsedDir.listFiles { _, name -> name.matches(Regex("chunk_\\d+_parsed\\.json")) }
        ?: emptyArray()

    if (files.isEmpty()) {
        System.err.println("No parsed chunk JSON files found in: ${parsedDir.absolutePath}")
        System.exit(1)
    }

    files.sortBy { file ->
        val num = file.name.substringAfter("chunk_").substringBefore("_parsed").toIntOrNull() ?: 0
        num
    }

    val objectMapper = jacksonObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val sqlBuilder = StringBuilder()

    sqlBuilder.append("-- Auto-generated seeding script for parsed figures metadata, steps, links, and comments\n")
    sqlBuilder.append("-- Source of truth: docs/figures steps/parsed/chunk_*_parsed.json\n\n")

    // Clean up existing links and steps first in bulk for clean transaction re-entrancy
    sqlBuilder.append("-- 1. Bulk delete existing crawled steps and links to avoid key violations during re-inserts\n")
    sqlBuilder.append("DELETE FROM dance_figure_link WHERE type = 'CRAWLED';\n")
    sqlBuilder.append("DELETE FROM dance_figure_step WHERE dance_figure_id IN (SELECT id FROM dance_figure WHERE predefined = true);\n\n")

    val figuresValues = mutableListOf<String>()
    val linksValues = mutableListOf<String>()
    val stepsValues = mutableListOf<String>()
    val commentsValues = mutableListOf<String>()

    val seenFigures = mutableSetOf<String>()
    val seenLinks = mutableSetOf<String>()
    val seenSteps = mutableSetOf<String>()
    val seenComments = mutableSetOf<String>()

    var figuresCount = 0
    var stepsCount = 0
    var linksCount = 0
    var commentsCount = 0

    for (file in files) {
        println("Processing JSON file for SQL generation: ${file.name}")
        val figures: List<AiParsedFigureDto> = try {
            objectMapper.readValue(file)
        } catch (e: Exception) {
            System.err.println("Error reading ${file.name}: ${e.message}")
            continue
        }

        for (record in figures) {
            val name = record.name ?: continue
            val jsonDanceType = record.dance_type ?: continue

            val dbDanceTypeName = mapDanceTypeJsonToDbName(jsonDanceType)
            val uniqueFigureKey = "${dbDanceTypeName.lowercase()}_${name.lowercase().trim()}"

            // If we've already defined this figure in this script run, skip it to avoid ON CONFLICT duplicate key errors
            if (seenFigures.contains(uniqueFigureKey)) {
                continue
            }
            seenFigures.add(uniqueFigureKey)

            val escapedName = escapeSql(name)
            val escapedDanceTypeName = escapeSql(dbDanceTypeName)

            // 1. Collect figure insert values
            val precedingNamesJson = serializeList(record.preceding_figure_names)
            val followingNamesJson = serializeList(record.following_figure_names)

            figuresValues.add("""
                (
                    gen_random_uuid(),
                    $escapedName,
                    (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName),
                    true,
                    ${escapeSql(record.starting_foot_leader)},
                    ${escapeSql(record.ending_foot_leader)},
                    ${escapeSql(record.starting_foot_follower)},
                    ${escapeSql(record.ending_foot_follower)},
                    ${escapeSql(record.starting_position)},
                    ${escapeSql(record.ending_position)},
                    ${escapeSql(precedingNamesJson)},
                    ${escapeSql(followingNamesJson)},
                    ${escapeSql(record.notes)}
                )
            """.trimIndent())
            figuresCount++

            // 2. Collect links
            val urls = mutableListOf<String>()
            if (record.urls != null) {
                urls.addAll(record.urls)
            }
            if (record.url != null && !urls.contains(record.url)) {
                urls.add(record.url)
            }

            for (url in urls) {
                val linkKey = "${dbDanceTypeName}_${name}_$url"
                if (seenLinks.contains(linkKey)) continue
                seenLinks.add(linkKey)

                val linkUuid = UUID.nameUUIDFromBytes(linkKey.toByteArray()).toString()

                linksValues.add("""
                    (
                        '$linkUuid',
                        (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName)),
                        ${escapeSql(url)},
                        'DanceCentral Reference',
                        'CRAWLED'
                    )
                """.trimIndent())
                linksCount++
            }

            // 3. Collect steps and comments
            if (record.steps != null) {
                for ((stepIndex, stepDto) in record.steps.withIndex()) {
                    val sn = stepDto.step_number
                    val stepNumber = when (sn) {
                        is Number -> sn.toInt()
                        is String -> sn.substringBefore("&").substringBefore(" ").trim().toIntOrNull() ?: (stepIndex + 1)
                        else -> stepIndex + 1
                    }
                    val rawRole = stepDto.role ?: ""
                    val role = if (rawRole.length > 50) rawRole.substring(0, 50) else rawRole
                    val stepKey = "${dbDanceTypeName}_${name}_${role}_${stepNumber}_$stepIndex"
                    
                    if (seenSteps.contains(stepKey)) continue
                    seenSteps.add(stepKey)

                    val stepUuid = UUID.nameUUIDFromBytes(stepKey.toByteArray()).toString()

                    val rawTiming = stepDto.timing ?: ""
                    val timing = if (rawTiming.length > 50) rawTiming.substring(0, 50) else rawTiming
                    val rawFoot = stepDto.foot ?: ""
                    val foot = if (rawFoot.length > 50) rawFoot.substring(0, 50) else rawFoot
                    val rawFootwork = stepDto.footwork
                    val footwork = if (rawFootwork != null && rawFootwork.length > 255) rawFootwork.substring(0, 255) else rawFootwork

                    stepsValues.add("""
                        (
                            '$stepUuid',
                            (SELECT id FROM dance_figure WHERE name = $escapedName AND dance_type_id = (SELECT id FROM dance_type WHERE name = $escapedDanceTypeName)),
                            $stepNumber,
                            ${escapeSql(timing)},
                            ${escapeSql(role)},
                            ${escapeSql(foot)},
                            ${escapeSql(stepDto.action)},
                            ${escapeSql(footwork)},
                            ${escapeSql(stepDto.alignment)},
                            ${escapeSql(stepDto.amount_of_turn)}
                        )
                    """.trimIndent())
                    stepsCount++

                    if (stepDto.comments != null) {
                        for ((commentIndex, commentText) in stepDto.comments.withIndex()) {
                            if (commentText.isNotBlank()) {
                                val commentKey = "${stepKey}_comment_$commentIndex"
                                if (seenComments.contains(commentKey)) continue
                                seenComments.add(commentKey)

                                val commentUuid = UUID.nameUUIDFromBytes(commentKey.toByteArray()).toString()

                                commentsValues.add("""
                                    (
                                        '$commentUuid',
                                        '$stepUuid',
                                        ${escapeSql(commentText)},
                                        ${commentIndex + 1}
                                    )
                                """.trimIndent())
                                commentsCount++
                            }
                        }
                    }
                }
            }
        }
    }

    // Write bulk insert statements to the file
    sqlBuilder.append("-- 2. Bulk insert / update figures\n")
    for (chunk in figuresValues.chunked(100)) {
        sqlBuilder.append("""
            INSERT INTO dance_figure (id, name, dance_type_id, predefined, starting_foot_leader, ending_foot_leader, starting_foot_follower, ending_foot_follower, starting_position, ending_position, preceding_figure_names, following_figure_names, notes)
            VALUES
        """.trimIndent()).append("\n")
        sqlBuilder.append(chunk.joinToString(",\n"))
        sqlBuilder.append("\nON CONFLICT (dance_type_id, name) DO UPDATE SET\n")
        sqlBuilder.append("""
            predefined = EXCLUDED.predefined,
            starting_foot_leader = EXCLUDED.starting_foot_leader,
            ending_foot_leader = EXCLUDED.ending_foot_leader,
            starting_foot_follower = EXCLUDED.starting_foot_follower,
            ending_foot_follower = EXCLUDED.ending_foot_follower,
            starting_position = EXCLUDED.starting_position,
            ending_position = EXCLUDED.ending_position,
            preceding_figure_names = EXCLUDED.preceding_figure_names,
            following_figure_names = EXCLUDED.following_figure_names,
            notes = EXCLUDED.notes;
        """.trimIndent()).append("\n\n")
    }

    sqlBuilder.append("-- 3. Bulk insert links\n")
    for (chunk in linksValues.chunked(200)) {
        sqlBuilder.append("""
            INSERT INTO dance_figure_link (id, dance_figure_id, url, title, type)
            VALUES
        """.trimIndent()).append("\n")
        sqlBuilder.append(chunk.joinToString(",\n"))
        sqlBuilder.append(";\n\n")
    }

    sqlBuilder.append("-- 4. Bulk insert steps\n")
    for (chunk in stepsValues.chunked(200)) {
        sqlBuilder.append("""
            INSERT INTO dance_figure_step (id, dance_figure_id, step_number, timing, role, foot, action, footwork, alignment, amount_of_turn)
            VALUES
        """.trimIndent()).append("\n")
        sqlBuilder.append(chunk.joinToString(",\n"))
        sqlBuilder.append(";\n\n")
    }

    sqlBuilder.append("-- 5. Bulk insert comments\n")
    for (chunk in commentsValues.chunked(200)) {
        sqlBuilder.append("""
            INSERT INTO dance_figure_step_comment (id, dance_figure_step_id, comment_text, display_order)
            VALUES
        """.trimIndent()).append("\n")
        sqlBuilder.append(chunk.joinToString(",\n"))
        sqlBuilder.append(";\n\n")
    }

    val migrationFile = Paths.get("src/main/resources/db/migration/V24__seed_figures_details.sql").toFile()
    migrationFile.parentFile.mkdirs()
    migrationFile.writeText(sqlBuilder.toString())

    println("SQL seed script successfully generated!")
    println("Output file: ${migrationFile.absolutePath}")
    println("Summary of generated records:")
    println("  - Figures: $figuresCount")
    println("  - Links: $linksCount")
    println("  - Steps: $stepsCount")
    println("  - Comments: $commentsCount")
}

private fun escapeSql(str: String?): String {
    if (str == null) return "NULL"
    return "'" + str.replace("'", "''") + "'"
}

private fun serializeList(field: Any?): String? {
    if (field == null) return null
    if (field is List<*>) {
        val cleanList = field.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        if (cleanList.isEmpty()) return null
        return jacksonObjectMapper().writeValueAsString(cleanList)
    }
    if (field is String) {
        val cleanList = field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanList.isEmpty()) return null
        return jacksonObjectMapper().writeValueAsString(cleanList)
    }
    return null
}

private fun mapDanceTypeJsonToDbName(jsonDanceType: String): String {
    return when (jsonDanceType.uppercase()) {
        "WALTZ" -> "Waltz"
        "TANGO" -> "Tango"
        "VIENNESE_WALTZ" -> "Viennese Waltz"
        "SLOW_FOXTROT", "FOXTROT" -> "Foxtrot"
        "QUICKSTEP" -> "Quickstep"
        "CHA_CHA_CHA", "CHA_CHA" -> "Cha Cha"
        "SAMBA" -> "Samba"
        "RUMBA" -> "Rumba"
        "PASO_DOBLE" -> "Paso Doble"
        "JIVE" -> "Jive"
        else -> jsonDanceType
    }
}
